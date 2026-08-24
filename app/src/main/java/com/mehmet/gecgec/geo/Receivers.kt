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
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.launch.AppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ==================== DIS CEMBER TETIKLEYICISI ====================

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return

        if (event.hasError()) {
            val msg = GeofenceStatusCodes.getStatusCodeString(event.errorCode)
            EventLog.add(context, "Cember hatasi: $msg")
            Log.e(GeofenceManager.TAG, "Geofence hatasi: $msg")
            return
        }

        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (ids.isEmpty()) return

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                EventLog.add(context, "Yaklastin (${ids.size} yer) - hassas takip basliyor")
                startProximity(context, ids)
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                EventLog.add(context, "Uzaklastin - takip durduruldu")
                context.stopService(Intent(context, ProximityService::class.java))
            }
        }
    }

    private fun startProximity(context: Context, ids: List<String>) {
        val svc = Intent(context, ProximityService::class.java)
            .putStringArrayListExtra(ProximityService.EXTRA_IDS, ArrayList(ids))
        try {
            ContextCompat.startForegroundService(context, svc)
        } catch (t: Throwable) {
            // Arka plandan servis baslatilamadiysa en azindan uygulamayi acmayi dene
            EventLog.add(context, "Takip servisi baslatilamadi, dogrudan aciliyor")
            CoroutineScope(Dispatchers.Default).launch {
                val places = PlaceStore(context).load()
                ids.forEach { id ->
                    places.firstOrNull { it.id == id && it.enabled }
                        ?.let { AppLauncher.fire(context, it, "cember") }
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
                EventLog.add(context, "Telefon yeniden basladi - cemberler kuruluyor")
                GeofenceManager(context.applicationContext).sync()
            } finally {
                pending.finish()
            }
        }
    }
}

// ==================== 20 METRE TAKIBI ====================

/**
 * Dis cembere girilince calisir. GPS'i hassas moda alip her 2-3 saniyede
 * konumu olcer; hedefe [Place.triggerMeters] kadar yaklasinca tetikler.
 * Kimse tetiklenmezse 12 dakika sonra kendini kapatir (pil icin).
 */
class ProximityService : Service() {

    private lateinit var client: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private var places: List<Place> = emptyList()
    private val watching = linkedSetOf<String>()
    private var callback: LocationCallback? = null
    private var closest = Double.MAX_VALUE

    private val autoStop = Runnable {
        val m = if (closest == Double.MAX_VALUE) "-" else "${closest.roundToInt()} m"
        EventLog.add(this, "Takip bitti, tetiklenmedi (en yakin: $m)")
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
        if (watching.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        scope.launch {
            places = PlaceStore(this@ProximityService).load()
            handler.post { startTracking() }
        }

        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 12 * 60 * 1000L)
        return START_NOT_STICKY
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Yaklasma takibi", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GecGec")
            .setContentText("Yaklastin, konum izleniyor...")
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
            EventLog.add(this, "Konum izni yok - takip yapilamadi")
            stopSelf()
            return
        }

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(2000L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocation(it) }
            }
        }
        client.requestLocationUpdates(req, callback!!, Looper.getMainLooper())
    }

    private fun onLocation(loc: Location) {
        val done = mutableListOf<String>()

        for (id in watching) {
            val p = places.firstOrNull { it.id == id && it.enabled && it.isReady } ?: continue
            val out = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, p.lat, p.lng, out)
            val d = out[0].toDouble()
            if (d < closest) closest = d

            if (d <= p.triggerMeters) {
                AppLauncher.fire(this, p, "${d.roundToInt()} m")
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
