package com.mehmet.gecgec.learn

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mehmet.gecgec.R
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.PlaceKind
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.data.PoiStore
import com.mehmet.gecgec.geo.GeofenceManager
import com.mehmet.gecgec.launch.AppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Kullanim verisi izni verilmis mi? */
fun Context.hasUsageAccess(): Boolean = runCatching {
    val ops = getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
    } else {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    }
    mode == AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

/** Izin varsa ogrenme servisini baslatir, yoksa hicbir sey yapmaz. */
fun Context.startLearnIfPossible() {
    if (!hasUsageAccess()) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
    ) return
    runCatching {
        ContextCompat.startForegroundService(this, Intent(this, LearnService::class.java))
    }
}

/**
 * KENDI KENDINE OGRENME
 *
 * Harita (OpenStreetMap) Turkiye'deki subelerin bir kismini bilmiyor.
 * Bu servis o eksigi kullanicinin kendi davranisindan kapatir:
 *
 *   Sok'a girip Sok uygulamasini KENDI actiysa, demek ki oradadir.
 *   O nokta o markanin subesi olarak kaydedilir ve bir daha elle
 *   acmasi gerekmez.
 *
 * Ucuz calisir: surekli GPS dinlemez. 45 saniyede bir sadece
 * "hedef uygulamalardan biri one geldi mi" diye bakar; ancak geldiyse
 * TEK bir konum olcumu ister.
 */
class LearnService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private var lastCheck = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, INTERVAL)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!hasUsageAccess()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            lastCheck = System.currentTimeMillis()
            handler.postDelayed(tick, INTERVAL)
        }
        // Telefon servisi oldururse Android geri baslatsin
        return START_STICKY
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Arka plan", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("GeçGeç açık")
            .setContentText("Yeni şubeleri öğreniyor")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }
    }

    private fun check() {
        val now = System.currentTimeMillis()
        val from = if (lastCheck == 0L) now - INTERVAL else lastCheck
        lastCheck = now

        scope.launch {
            runCatching { learn(from, now) }
        }
    }

    private suspend fun learn(from: Long, to: Long) {
        val ctx = this@LearnService

        val brands = PlaceStore(ctx).load().filter {
            it.enabled && it.kind == PlaceKind.BRAND && it.targetPackage.isNotEmpty()
        }
        if (brands.isEmpty()) return

        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        val events = usm.queryEvents(from - 2000L, to)
        val e = UsageEvents.Event()
        var hit: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // MOVE_TO_FOREGROUND (=1) ile ACTIVITY_RESUMED ayni sayidir
            if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            if (brands.any { it.targetPackage == e.packageName }) hit = e.packageName
        }

        val pkg = hit ?: return
        // GecGec'in kendisi actiysa yeni bilgi yok
        if (AppLauncher.launchedRecently(ctx, pkg)) return

        val place = brands.firstOrNull { it.targetPackage == pkg } ?: return
        val loc = currentLocation() ?: return
        // Kotu konum yanlis subeyi kaydeder - hassas degilse hic ugrasma
        if (!loc.hasAccuracy() || loc.accuracy > 120f) return

        val added = PoiStore(ctx).addManual(
            place.id, loc.latitude, loc.longitude, "${place.name} (öğrenildi)"
        )
        if (added) {
            EventLog.add(ctx, "${place.name}: yeni şube öğrenildi ✓ (buradan)")
            GeofenceManager(ctx).sync()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? = runCatching {
        val c = LocationServices.getFusedLocationProviderClient(this)
        c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() ?: c.lastLocation.await()
    }.getOrNull()

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "gecgec_watch"
        private const val NOTIF_ID = 77
        private const val INTERVAL = 45_000L
    }
}
