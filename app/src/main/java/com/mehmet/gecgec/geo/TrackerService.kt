package com.mehmet.gecgec.geo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mehmet.gecgec.R
import com.mehmet.gecgec.data.Area
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.PlaceKind
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.data.PoiStore
import com.mehmet.gecgec.data.SettingsStore
import com.mehmet.gecgec.data.Target
import com.mehmet.gecgec.data.TrackMode
import com.mehmet.gecgec.data.buildTargets
import com.mehmet.gecgec.data.distanceMeters
import com.mehmet.gecgec.launch.AppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

/** Kullanim verisi izni verilmis mi? (sube ogrenme icin) */
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

/**
 * Takip servisinin izleyecegi hedefler.
 *
 * Cember kurarken Android bizi 100 nokta ile sinirliyor, ama bu servis
 * mesafeyi kendisi hesapliyor - o sinir burada gecerli degil. Sehirdeki
 * subelerin HEPSI izlenir. 600 nokta icin mesafe hesabi milisaniyenin
 * altinda surer, pil farki yok.
 */
internal suspend fun loadAllTargets(context: Context): List<Target> =
    buildTargets(
        PlaceStore(context).load(),
        PoiStore(context).load(),
        max = 600,
        perBrand = 300
    )

/** Takip servisini baslatir. Izin yoksa veya kapatilmissa hicbir sey yapmaz. */
fun Context.startTrackerIfPossible() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
    ) return
    runCatching {
        ContextCompat.startForegroundService(this, Intent(this, TrackerService::class.java))
    }
}

/**
 * SUREKLI AKILLI TAKIP
 *
 * NEDEN VAR:
 * Android'in cember (geofence) servisi bir bolgeye girdigini 1-3 DAKIKA
 * gecikmeyle haber veriyor. 90 km/h'te bu 1,5 kilometre demek - Sok'u
 * coktan gecmis oluyorsun. O gecikme Google'in servisinde, hizlandirilamiyor.
 *
 * Bu servis cemberi beklemez, konumu KENDISI olcer. Gecikme sifirlanir.
 *
 * PILI NASIL KORUR:
 * Surekli GPS acik degil. Mesafeye ve hiza gore dort kademe var:
 *
 *   DURGUN  - 10 dakikadir kimildamadin      -> 5 dakikada bir, dusuk guc
 *   UZAK    - en yakin sube 6 km'den uzak    -> 90 saniyede bir, dusuk guc
 *   YAKIN   - 6 km'nin icindesin             -> 20 saniyede bir, dusuk guc
 *   AV      - tetiklenme mesafesine giriyor  -> saniyede bir, TAM GPS
 *
 * "AV" kademesine gecis hiza bagli: yayayken 1,2 km'de, 90 km/h'te 3 km'de
 * baslar. Yani araba hizinda bile hedefe varmadan tam GPS acilmis olur.
 *
 * Ayrica hedef uygulamayi kendin actiginda o noktayi sube olarak ogrenir.
 */
class TrackerService : Service() {

    private lateinit var client: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ui = Handler(Looper.getMainLooper())

    /** Konum olcumleri ana is parcaciginda karsilanmaz - uygulama donar. */
    private var worker: HandlerThread? = null
    private var callback: LocationCallback? = null

    private var mode = TrackMode.FULL
    private var tier = -1
    private var targets: List<Target> = emptyList()

    private var lastLoc: Location? = null
    private var area = ""
    private var stillSince = 0L
    private var lastRefreshCheck = 0L
    private var lastUsageCheck = 0L
    private var lastNotifText = ""
    private var lastNotifAt = 0L

    private val minDist = mutableMapOf<String, Double>()
    private val lastDist = mutableMapOf<String, Double>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        notify("Başlatılıyor…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notify(lastNotifText.ifBlank { "Başlatılıyor…" })

        scope.launch {
            mode = SettingsStore(this@TrackerService).load()
            if (mode == TrackMode.OFF) {
                ui.post { stopSelf() }
                return@launch
            }
            targets = loadAllTargets(this@TrackerService)
            ui.post { applyTier(TIER_FAR, force = true) }
        }
        return START_STICKY
    }

    // ---- Konum akisi ----

    @SuppressLint("MissingPermission")
    private fun applyTier(newTier: Int, force: Boolean = false) {
        if (!force && newTier == tier) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        tier = newTier

        val slow = if (mode == TrackMode.SAVER) 3 else 1
        val (priority, intervalMs) = when (newTier) {
            TIER_HUNT -> Priority.PRIORITY_HIGH_ACCURACY to 1_000L * slow
            TIER_NEAR -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to 20_000L * slow
            TIER_IDLE -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to 300_000L
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to 90_000L * slow
        }

        callback?.let { client.removeLocationUpdates(it) }

        val req = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocation(it) }
            }
        }
        callback = cb

        if (worker == null) {
            worker = HandlerThread("gecgec-takip").also { it.start() }
        }
        client.requestLocationUpdates(req, cb, worker!!.looper)
    }

    private fun onLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0   // m/s

        // --- Durgunluk: 10 dakikadir 60 metreden az hareket ---
        val prev = lastLoc
        val moved = prev?.let { distanceMeters(it.latitude, it.longitude, loc.latitude, loc.longitude) } ?: 0.0
        if (prev == null || moved > 60.0) stillSince = now
        lastLoc = loc
        val still = now - stillSince > 10 * 60_000L

        // --- En yakin hedef ---
        var nearest: Target? = null
        var nearestD = Double.MAX_VALUE
        for (t in targets) {
            val d = distanceMeters(loc.latitude, loc.longitude, t.lat, t.lng)
            if (d < nearestD) { nearestD = d; nearest = t }
            checkTrigger(t, d, speed)
        }

        // --- Kademe secimi ---
        // Hiz payi: 90 km/h (25 m/s) -> 3 km'de tam GPS acilir.
        val huntAt = (1500.0 + speed * 80.0).coerceAtMost(4500.0)
        val next = when {
            targets.isEmpty() -> TIER_IDLE
            nearestD <= huntAt -> TIER_HUNT
            still -> TIER_IDLE
            nearestD <= 6000.0 -> TIER_NEAR
            else -> TIER_FAR
        }
        if (next != tier) ui.post { applyTier(next) }

        maybeRefresh(loc, now)
        maybeLearn(now)
        showState(nearest, nearestD, still, now)
    }

    /** Tetikleme karari - ProximityService ile ayni mantik. */
    private fun checkTrigger(t: Target, d: Double, speed: Double) {
        val id = t.fenceId
        val was = lastDist[id] ?: Double.MAX_VALUE
        val seen = minDist[id] ?: Double.MAX_VALUE
        if (d < seen) minDist[id] = d
        lastDist[id] = d

        val eff = (t.place.triggerMeters * (1.0 + speed.coerceAtMost(30.0) / 7.0))
            .coerceAtMost(t.place.fenceMeters.toDouble())

        // Gecip gidiyorsak: en yakin noktayi gectik ama yeterince yaklasmistik
        val movingAway = d > was + 8
        val wasClose = (minDist[id] ?: Double.MAX_VALUE) <= eff * 2

        if (d <= eff || (movingAway && wasClose)) {
            val kmh = (speed * 3.6).roundToInt()
            val why = if (kmh > 8) "${d.roundToInt()} m · $kmh km/s · ${t.label}"
            else "${d.roundToInt()} m · ${t.label}"
            AppLauncher.fire(this, t.place, why, id)
            minDist.remove(id)
            lastDist.remove(id)
        }
    }

    /**
     * Mahalle degistiyse ya da yeterince uzaklastiysan sube listesini tazele.
     *
     * Mahalle kontrolu asil olan: 800 metre yurumeden semt degistirebilirsin
     * (arabayla iki dakikada). Isim degisince liste aninda yenilenir.
     */
    private fun maybeRefresh(loc: Location, now: Long) {
        if (now - lastRefreshCheck < 45_000L) return
        lastRefreshCheck = now
        val ctx = this

        scope.launch {
            runCatching {
                val store = PoiStore(ctx)
                val cache = store.load()

                val newArea = Area.name(ctx, loc.latitude, loc.longitude)
                val areaChanged = newArea.isNotBlank() && area.isNotBlank() && newArea != area
                if (newArea.isNotBlank()) area = newArea

                if (areaChanged || store.isStale(cache, loc.latitude, loc.longitude)) {
                    if (areaChanged) EventLog.add(ctx, "Mahalle değişti: $newArea — şubeler yenileniyor")
                    GeofenceManager(ctx).sync(forceRefresh = true)
                }
                targets = loadAllTargets(ctx)
            }
        }
    }

    /** Hedef uygulamayi kendisi actiysa orayi sube olarak ogren. */
    private fun maybeLearn(now: Long) {
        if (!hasUsageAccess()) return
        if (now - lastUsageCheck < 45_000L) return
        val from = if (lastUsageCheck == 0L) now - 45_000L else lastUsageCheck
        lastUsageCheck = now

        scope.launch {
            runCatching {
                val ctx = this@TrackerService
                val brands = PlaceStore(ctx).load().filter {
                    it.enabled && it.kind == PlaceKind.BRAND && it.targetPackage.isNotEmpty()
                }
                if (brands.isEmpty()) return@runCatching

                val usm = getSystemService(UsageStatsManager::class.java) ?: return@runCatching
                val events = usm.queryEvents(from - 2000L, now)
                val e = UsageEvents.Event()
                var hit: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                    if (brands.any { it.targetPackage == e.packageName }) hit = e.packageName
                }

                val pkg = hit ?: return@runCatching
                if (AppLauncher.launchedRecently(ctx, pkg)) return@runCatching
                val place = brands.firstOrNull { it.targetPackage == pkg } ?: return@runCatching

                val loc = lastLoc ?: return@runCatching
                if (!loc.hasAccuracy() || loc.accuracy > 120f) return@runCatching

                val added = PoiStore(ctx).addManual(
                    place.id, loc.latitude, loc.longitude, "${place.name} (öğrenildi)"
                )
                if (added) {
                    EventLog.add(ctx, "${place.name}: yeni şube öğrenildi ✓ (buradan)")
                    GeofenceManager(ctx).sync()
                    targets = loadAllTargets(ctx)
                }
            }
        }
    }

    // ---- Bildirim ----

    private fun showState(nearest: Target?, d: Double, still: Boolean, now: Long) {
        val yer = when {
            targets.isEmpty() -> "izlenecek yer yok"
            still && tier == TIER_IDLE -> "bekleniyor"
            nearest == null -> "konum aranıyor…"
            d > 1200 -> "%s · %.1f km".format(nearest.label, d / 1000)
            else -> "${nearest.label} · ${d.roundToInt()} m"
        }
        // Mahalle adi varsa basa yazilir: "Liman Mah., Konyaaltı · Şok · 340 m"
        val text = if (area.isBlank()) yer else "$area · $yer"

        if (text == lastNotifText && now - lastNotifAt < 60_000L) return
        lastNotifText = text
        lastNotifAt = now
        ui.post { notify(text) }
    }

    private fun notify(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Takip", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("GeçGeç")
            .setContentText(text)
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

    override fun onDestroy() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        worker?.quitSafely()
        worker = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "gecgec_track"
        private const val NOTIF_ID = 91

        private const val TIER_HUNT = 0
        private const val TIER_NEAR = 1
        private const val TIER_FAR = 2
        private const val TIER_IDLE = 3
    }
}
