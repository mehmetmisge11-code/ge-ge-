package com.mehmet.gecgec.geo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mehmet.gecgec.R
import com.mehmet.gecgec.data.AREA_FENCE_ID
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.data.PoiStore
import com.mehmet.gecgec.data.Target
import com.mehmet.gecgec.data.buildTargets
import com.mehmet.gecgec.data.distanceMeters
import com.mehmet.gecgec.launch.AppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ==================== CEMBER TETIKLEYICISI ====================

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return

        if (event.hasError()) {
            val msg = GeofenceStatusCodes.getStatusCodeString(event.errorCode)
            EventLog.add(context, "Çember hatası: $msg")
            Log.e(GeofenceManager.TAG, "Geofence hatasi: $msg")
            return
        }

        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (ids.isEmpty()) return

        // Bolgeden cikildi -> yakindaki subeleri yeniden cek
        if (ids.contains(AREA_FENCE_ID)) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    EventLog.add(context, "Bölge değişti — şubeler yenileniyor")
                    GeofenceManager(context.applicationContext).sync(forceRefresh = true)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        EventLog.add(context, "Yaklaştın (${ids.size} nokta) — hassas takip başlıyor")
        startProximity(context, ids)
    }

    private fun startProximity(context: Context, ids: List<String>) {
        val svc = Intent(context, ProximityService::class.java)
            .putStringArrayListExtra(ProximityService.EXTRA_IDS, ArrayList(ids))
        try {
            ContextCompat.startForegroundService(context, svc)
        } catch (t: Throwable) {
            EventLog.add(context, "Takip servisi başlatılamadı, doğrudan açılıyor")
            CoroutineScope(Dispatchers.Default).launch {
                val targets = loadTargets(context)
                ids.forEach { id ->
                    targets.firstOrNull { it.fenceId == id }
                        ?.let { AppLauncher.fire(context, it.place, "cember", it.fenceId) }
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                EventLog.add(context, "Telefon yeniden başladı — çemberler kuruluyor")
                GeofenceManager(context.applicationContext).sync()
            } finally {
                pending.finish()
            }
        }
    }
}

private suspend fun loadTargets(context: Context): List<Target> =
    buildTargets(PlaceStore(context).load(), PoiStore(context).load())

// ==================== 20 METRE TAKIBI ====================

/**
 * Dis cembere girilince calisir. GPS'i hassas moda alip her 2-3 saniyede
 * konumu olcer; hedefe [com.mehmet.gecgec.data.Place.triggerMeters] kadar
 * yaklasinca tetikler. Kimse tetiklenmezse 12 dakika sonra kendini kapatir.
 */
class ProximityService : Service() {

    private lateinit var client: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private var targets: List<Target> = emptyList()
    private val watching = linkedSetOf<String>()
    private var callback: LocationCallback? = null
    private var closest = Double.MAX_VALUE
    private var lastSpeed = 0.0

    /** Her hedef icin simdiye kadarki en yakin ve bir onceki mesafe. */
    private val minDist = mutableMapOf<String, Double>()
    private val lastDist = mutableMapOf<String, Double>()

    private val autoStop = Runnable {
        if (closest == Double.MAX_VALUE) {
            EventLog.add(this, "Takip bitti — konum hiç alınamadı")
        } else {
            val m = closest.roundToInt()
            val need = targets.filter { watching.contains(it.fenceId) }
                .minOfOrNull { it.place.triggerMeters } ?: 0f
            if (need > 0f && closest > need) {
                EventLog.add(
                    this,
                    "Tetiklenmedi: en yakın $m m geldin, ayar ${need.roundToInt()} m. " +
                        "Ayarlar'dan mesafeyi ${(closest * 1.4).roundToInt()} m yaparsan çalışır." +
                        if (lastSpeed > 3) " (son hız ${(lastSpeed * 3.6).roundToInt()} km/s)" else ""
                )
            } else {
                EventLog.add(this, "Takip bitti (en yakın: $m m)")
            }
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()

        intent?.getStringArrayListExtra(EXTRA_IDS)?.let { watching += it }
        if (watching.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            targets = loadTargets(this@ProximityService)
            handler.post { startTracking() }
        }

        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 12 * 60 * 1000L)
        return START_NOT_STICKY
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Yaklaşma takibi", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("GeçGeç")
            .setContentText("Yaklaştın, konum izleniyor…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (callback != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.add(this, "Konum izni yok — takip yapılamadı")
            stopSelf()
            return
        }

        // Saniyede bir olcum: 50 km/h'te iki olcum arasi 14 metre.
        // 3 saniyede bir olsaydi 42 metre atlardi ve hedefi kacirirdik.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocation(it) }
            }
        }
        client.requestLocationUpdates(req, callback!!, Looper.getMainLooper())
    }

    private fun onLocation(loc: Location) {
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0   // m/s
        lastSpeed = speed
        val done = mutableListOf<String>()

        for (id in watching) {
            val t = targets.firstOrNull { it.fenceId == id } ?: continue
            val d = distanceMeters(loc.latitude, loc.longitude, t.lat, t.lng)

            val prev = lastDist[id] ?: Double.MAX_VALUE
            val seen = minDist[id] ?: Double.MAX_VALUE
            if (d < seen) minDist[id] = d
            lastDist[id] = d
            if (d < closest) closest = d

            // Hiz payi: yayayken ayarladigin mesafe, arabadayken cok daha erken.
            // 50 km/h (14 m/s) -> ayar 40 m ise ~120 m'de tetikler.
            val eff = (t.place.triggerMeters * (1.0 + speed.coerceAtMost(30.0) / 7.0))
                .coerceAtMost(t.place.fenceMeters.toDouble())

            // Gecip gidiyorsak: en yakin noktayi gectik ve yeterince yaklastik
            val movingAway = d > prev + 8
            val wasClose = (minDist[id] ?: Double.MAX_VALUE) <= eff * 2

            if (d <= eff || (movingAway && wasClose)) {
                val kmh = (speed * 3.6).roundToInt()
                val why = if (kmh > 8) "${d.roundToInt()} m · $kmh km/s · ${t.label}"
                else "${d.roundToInt()} m · ${t.label}"
                AppLauncher.fire(this, t.place, why, t.fenceId)
                done += id
            }
        }

        watching -= done.toSet()
        if (watching.isEmpty()) stopSelf()
    }

    override fun onDestroy() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        handler.removeCallbacks(autoStop)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IDS = "ids"
        private const val CHANNEL = "gecgec_tracking"
        private const val NOTIF_ID = 42
    }
}
