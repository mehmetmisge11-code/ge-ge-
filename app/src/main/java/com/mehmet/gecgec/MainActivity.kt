package com.mehmet.gecgec

import android.Manifest
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.KeyStore
import com.mehmet.gecgec.data.MapSearch
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.data.PlaceKind
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.data.Poi
import com.mehmet.gecgec.data.PoiStore
import com.mehmet.gecgec.data.brandPois
import com.mehmet.gecgec.data.buildTargets
import com.mehmet.gecgec.data.distanceMeters
import com.mehmet.gecgec.geo.GeofenceManager
import com.mehmet.gecgec.launch.AppLauncher
import com.mehmet.gecgec.launch.InstalledApp
import com.mehmet.gecgec.launch.installedLaunchableApps
import com.mehmet.gecgec.learn.hasUsageAccess
import com.mehmet.gecgec.learn.startLearnIfPossible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Hedef uygulamanin amblemi + o amblemden cikarilan marka rengi. */
data class AppIcon(val image: ImageBitmap, val accent: Color)

/**
 * Amblemin marka rengini bulur.
 *
 * Renklerin duz ortalamasi alinmaz - kirmizi + sari + beyazin ortalamasi
 * camur bir renk verir. Bunun yerine:
 *   1. Pikseller 24 ton grubuna ayrilir, her piksel doygunlugu kadar agirlikli sayilir
 *      (kucuk ama canli bir amblem, buyuk ve solgun bir zemini yener)
 *   2. En agir grup secilir
 *   3. Ikinci grup ona hem yakin hem guclu ise ikisi karistirilir
 *      (McDonald's'in kirmizisi + sarisi -> turuncu)
 *   4. Cikan renk canlandirilir - solgun kalirsa koyu zeminde kaybolur
 */
private const val HUE_BUCKETS = 24

private fun hueGap(a: Float, b: Float): Float {
    val d = kotlin.math.abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

private fun dominantAccent(bmp: Bitmap): Color {
    val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)

    val weight = FloatArray(HUE_BUCKETS)
    val sr = FloatArray(HUE_BUCKETS)
    val sg = FloatArray(HUE_BUCKETS)
    val sb = FloatArray(HUE_BUCKETS)
    val hsv = FloatArray(3)

    for (y in 0 until small.height) {
        for (x in 0 until small.width) {
            val p = small.getPixel(x, y)
            if (AndroidColor.alpha(p) < 128) continue
            AndroidColor.colorToHSV(p, hsv)
            val sat = hsv[1]
            val vaL = hsv[2]
            // Beyaz, siyah ve griler marka rengi degildir.
            // DIKKAT: parlakliga ust sinir KOYMA - Sok'un ve McDonald's'in
            // sarisi parlak oldugu icin eleniyordu. Beyazi zaten doygunluk eliyor.
            if (sat < 0.20f || vaL < 0.15f) continue

            val w = sat * sat * vaL
            val b = ((hsv[0] / (360f / HUE_BUCKETS)).toInt()).coerceIn(0, HUE_BUCKETS - 1)
            weight[b] += w
            sr[b] += AndroidColor.red(p) * w
            sg[b] += AndroidColor.green(p) * w
            sb[b] += AndroidColor.blue(p) * w
        }
    }

    val best = weight.indices.maxByOrNull { weight[it] } ?: return Color(0xFF7CC33F)
    if (weight[best] <= 0f) return Color(0xFF7CC33F)

    var r = sr[best] / weight[best]
    var g = sg[best] / weight[best]
    var b2 = sb[best] / weight[best]

    // Ikinci en guclu grup: yakin bir tonsa karistir, uzaksa karistirma
    val second = weight.indices
        .filter { it != best && weight[it] > 0f }
        .maxByOrNull { weight[it] }

    if (second != null && weight[second] > weight[best] * 0.45f) {
        val step = 360f / HUE_BUCKETS
        val h1 = best * step + step / 2f
        val h2 = second * step + step / 2f
        if (hueGap(h1, h2) <= 70f) {
            val k = weight[second] / (weight[best] + weight[second])
            r = r * (1 - k) + (sr[second] / weight[second]) * k
            g = g * (1 - k) + (sg[second] / weight[second]) * k
            b2 = b2 * (1 - k) + (sb[second] / weight[second]) * k
        }
    }

    // Canlandir: koyu zeminde okunacak kadar doygun ve parlak olsun
    AndroidColor.colorToHSV(
        AndroidColor.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b2.toInt().coerceIn(0, 255)),
        hsv
    )
    hsv[1] = hsv[1].coerceAtLeast(0.78f)
    hsv[2] = hsv[2].coerceIn(0.82f, 1.0f)
    return Color(AndroidColor.HSVToColor(hsv))
}

/** Bir kere hesaplanan amblem/renk onbellegi - her cizimde yeniden uretmeyelim. */
private val iconCache = mutableMapOf<String, AppIcon?>()

/**
 * Amblemi ARKA PLANDA hazirlar. Ana is parcacigi beklemez, ekran donmaz.
 * Hazir olana kadar satirda emoji gorunur, sonra kendiliginden amblem gelir.
 */
@Composable
fun rememberAppIcon(pkg: String): AppIcon? {
    val context = LocalContext.current
    return produceState<AppIcon?>(initialValue = iconCache[pkg], key1 = pkg) {
        if (iconCache.containsKey(pkg)) {
            value = iconCache[pkg]
            return@produceState
        }
        val loaded = withContext(Dispatchers.IO) { context.appIcon(pkg) }
        iconCache[pkg] = loaded
        value = loaded
    }.value
}

fun Context.appIcon(pkg: String): AppIcon? {
    if (pkg.isBlank()) return null
    return runCatching {
        val d = packageManager.getApplicationIcon(pkg)
        val w = d.intrinsicWidth.coerceIn(1, 512)
        val h = d.intrinsicHeight.coerceIn(1, 512)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        AppIcon(bmp.asImageBitmap(), dominantAccent(bmp))
    }.getOrNull()
}

/** Sadece amblem - kilit ekrani karti bunu kullaniyor. */
fun Context.appIconBitmap(pkg: String): ImageBitmap? {
    if (pkg.isBlank()) return null
    return runCatching {
    val d = packageManager.getApplicationIcon(pkg)
    val w = d.intrinsicWidth.coerceIn(1, 512)
    val h = d.intrinsicHeight.coerceIn(1, 512)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, w, h)
    d.draw(Canvas(bmp))
    bmp.asImageBitmap()
    }.getOrNull()
}

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        // Ogrenme servisi izin verilmisse ayakta kalsin
        startLearnIfPossible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = GecGecDark) {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SplashGate {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) { Root() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    var skipTick by remember { mutableIntStateOf(0) }
    val steps = rememberSetupSteps(skipTick)
    val next = steps.firstOrNull { !it.done }
    if (next != null) {
        SetupScreen(next, steps.count { it.done }, steps.size) {
            context.skipSetup(next.skipKey)
            skipTick++
        }
    } else {
        HomeScreen()
    }
}

// ==================== KURULUM SIHIRBAZI ====================

private data class SetupStep(
    val title: String, val hint: String, val buttonText: String,
    val done: Boolean,
    /** Atlanabilir adimlar icin "Şimdilik geç" cikar. */
    val skippable: Boolean = false,
    val skipKey: String = "",
    val action: () -> Unit
)

private const val SETUP_PREFS = "gecgec_setup"

private fun Context.setupSkipped(key: String): Boolean =
    getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE).getBoolean(key, false)

private fun Context.skipSetup(key: String) {
    getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(key, true).apply()
}

@Composable
private fun rememberSetupSteps(extra: Int = 0): List<SetupStep> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val askFine = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }
    val askBg = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tick++ }
    val askNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tick++ }
    val openSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { tick++ }

    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    return remember(tick, extra) {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)
        val overlay = Settings.canDrawOverlays(context)
        val battery = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        // Android 14'ten once bu izin kendiliginden verilmis sayilir
        val fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

        listOf(
            SetupStep("Konum izni", "Nerede olduğunu bilmem lazım.", "İzin ver", fine) {
                askFine.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            SetupStep(
                "\"Her zaman izin ver\"",
                "Açılan ekranda konum için \"Her zaman izin ver\"i seç. " +
                    "Bu olmadan telefon cebindeyken çalışmaz.",
                "Aç", bg
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    askBg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            SetupStep(
                "Üstte gösterme izni",
                "Açılan listede GeçGeç'i bul ve aç. " +
                    "Android uygulamayı ancak bu izinle kendiliğinden açtırıyor.",
                "Aç", overlay
            ) {
                openSettings.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            },
            SetupStep(
                "Pili kısıtlama",
                "Listenin üstündeki menüden \"Tümü\"nü seç, GeçGeç'i bul, anahtarı KAPAT.",
                "Aç", battery
            ) {
                openSettings.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            SetupStep(
                "Tam ekran uyarı",
                "Açılan listede GeçGeç'i aç. Alarm uygulamalarının kullandığı izin — " +
                    "kilit ekranında kartın kesin çıkmasını bu sağlıyor.",
                "Aç", fullScreen
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    openSettings.launch(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            },
            SetupStep("Bildirim izni", "Uyarı gönderebilmem için.", "İzin ver", notif) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            SetupStep(
                title = "Şube öğrenme",
                hint = "Açılan listede GeçGeç'i bul ve aç. Bu izinle, haritanın " +
                    "bilmediği bir Şok'ta uygulamayı bir kere kendin açtığında " +
                    "GeçGeç orayı kendiliğinden kaydeder.\n\n" +
                    "İstemiyorsan geç — uygulama yine çalışır.",
                buttonText = "Aç",
                done = context.hasUsageAccess() || context.setupSkipped("skip_usage"),
                skippable = true,
                skipKey = "skip_usage"
            ) {
                openSettings.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        )
    }
}

@Composable
private fun SetupScreen(step: SetupStep, doneCount: Int, total: Int, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Kurulum - ${doneCount + 1}/$total", color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(20.dp))
        Text(step.title, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(step.hint, textAlign = TextAlign.Center, fontSize = 16.sp)
        Spacer(Modifier.height(36.dp))
        Button(onClick = step.action, modifier = Modifier.fillMaxWidth().height(60.dp)) {
            Text(step.buttonText, fontSize = 18.sp)
        }
        if (step.skippable) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Şimdilik geç", color = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { doneCount / total.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ==================== ANA EKRAN ====================

/** Her yer icin: kac sube var, en yakini kac metre. */
private data class PlaceInfo(val count: Int, val nearest: Double?)

private data class Status(
    val fenceCount: Int = 0,
    val nearestName: String = "",
    val nearestMeters: Double = -1.0,
    val nearestTrigger: Float = 0f,
    val lastScan: Long = 0L,
    val checking: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PlaceStore(context) }
    val poiStore = remember { PoiStore(context) }
    val geo = remember { GeofenceManager(context) }
    val places by store.placesFlow.collectAsStateWithLifecycle(emptyList())

    var editing by remember { mutableStateOf<Place?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(Status()) }
    var info by remember { mutableStateOf<Map<String, PlaceInfo>>(emptyMap()) }
    var refreshing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var branchesOf by remember { mutableStateOf<Place?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var sweeping by remember { mutableStateOf<String?>(null) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) { store.ensureSeeded() }
    LaunchedEffect(places) {
        if (places.isNotEmpty()) withContext(Dispatchers.IO) { geo.sync(places) }
    }

    LaunchedEffect(places, tick) {
        status = status.copy(checking = true)
        val cache = withContext(Dispatchers.IO) { poiStore.load() }
        val targets = withContext(Dispatchers.Default) { buildTargets(places, cache) }
        val here = currentLocation(context)
        val nearest = here?.let { (la, ln) ->
            targets.minByOrNull { distanceMeters(la, ln, it.lat, it.lng) }
        }
        status = Status(
            fenceCount = targets.size,
            nearestName = nearest?.label.orEmpty(),
            nearestMeters = if (nearest != null && here != null)
                distanceMeters(here.first, here.second, nearest.lat, nearest.lng) else -1.0,
            nearestTrigger = nearest?.place?.triggerMeters ?: 0f,
            lastScan = cache.updatedAt,
            checking = false
        )
        info = targets.groupBy { it.place.id }.mapValues { (_, list) ->
            PlaceInfo(
                count = list.size,
                nearest = here?.let { (la, ln) -> list.minOf { distanceMeters(la, ln, it.lat, it.lng) } }
            )
        }
    }

    val log = remember(tick, showLog) { EventLog.read(context) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                withContext(Dispatchers.IO) { geo.sync(places, forceRefresh = true) }
                tick++
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "GeçGeç",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-1).sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "YERLER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            items(places, key = { it.id }) { p ->
                PlaceRow(
                    place = p,
                    info = info[p.id],
                    expanded = expandedId == p.id,
                    onTap = { expandedId = if (expandedId == p.id) null else p.id },
                    onToggle = {
                        scope.launch { store.update(p.id) { it.copy(enabled = !it.enabled) } }
                    },
                    onTest = { AppLauncher.test(context, p); tick++ },
                    onSetup = { editing = p; editingIsNew = false },
                    onDelete = { scope.launch { store.delete(p.id) }; expandedId = null },
                    onBranches = { branchesOf = p },
                    onAddHere = {
                        scope.launch {
                            val loc = currentLocation(context)
                            if (loc == null) {
                                toast = "Konum alınamadı, açık havada tekrar dene."
                            } else {
                                val ok = poiStore.addManual(
                                    p.id, loc.first, loc.second, "${p.name} (elle)"
                                )
                                toast = if (ok) "${p.name} şubesi eklendi ✓"
                                else "Burası zaten kayıtlı."
                                if (ok) {
                                    EventLog.add(context, "${p.name}: şube elle eklendi")
                                    withContext(Dispatchers.IO) { geo.sync() }
                                }
                                tick++
                            }
                        }
                    },
                    onSweep = {
                        scope.launch {
                            sweeping = "${p.name}: şehir taranıyor…"
                            val loc = currentLocation(context)
                            if (loc == null) {
                                sweeping = null
                                toast = "Konum alınamadı."
                            } else {
                                val found = withContext(Dispatchers.IO) {
                                    MapSearch.sweepCity(
                                        context, p.searchText, loc.first, loc.second
                                    ) { d, t -> sweeping = "${p.name}: taranıyor $d/$t" }
                                }
                                val added = poiStore.addManualBulk(p.id, found)
                                withContext(Dispatchers.IO) { geo.sync() }
                                sweeping = null
                                toast = "${p.name}: $added yeni şube eklendi " +
                                    "(toplam ${found.size} bulundu)"
                                tick++
                            }
                        }
                    }
                )
            }

            item {
                Text(
                    "+ Yeni yer ekle",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            editing = Place(name = "", emoji = "📍")
                            editingIsNew = true
                        }
                        .padding(vertical = 16.dp, horizontal = 13.dp)
                )
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showSettings = !showSettings }
                        .padding(vertical = 8.dp, horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (showSettings) "▾" else "▸", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    Text("Harita kaynağı", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline)
                }
            }

            if (showSettings) item { HereKeyBox() }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showLog = !showLog }
                        .padding(vertical = 8.dp, horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (showLog) "▾" else "▸", fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    Text("Kayıtlar", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.weight(1f))
                    if (showLog) {
                        Text(
                            "Temizle",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { EventLog.clear(context); tick++ }
                        )
                    }
                }
            }

            if (showLog) {
                item { StatusCard(status) }
                if (log.isEmpty()) {
                    item {
                        Text(
                            "Henüz kayıt yok.", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 13.dp)
                        )
                    }
                }
                items(log) { line ->
                    Text(
                        line, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 13.dp)
                    )
                }
            }
        }
    }

    branchesOf?.let { p ->
        BranchesDialog(
            place = p,
            onDismiss = { branchesOf = null },
            onChanged = { scope.launch { withContext(Dispatchers.IO) { geo.sync() }; tick++ } }
        )
    }

    sweeping?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Şehir taranıyor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(msg, fontSize = 14.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Şehrin tamamı 25 parçaya bölünüp taranıyor. Biraz sürebilir.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        )
    }

    toast?.let { msg ->
        AlertDialog(
            onDismissRequest = { toast = null },
            confirmButton = { TextButton(onClick = { toast = null }) { Text("Tamam") } },
            text = { Text(msg) }
        )
    }

    editing?.let { place ->
        PlaceDialog(
            place = place,
            isNew = editingIsNew,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch {
                    if (editingIsNew) store.add(updated) else store.update(updated.id) { updated }
                    editing = null
                    tick++
                }
            }
        )
    }
}

// ==================== HARITA KAYNAGI ====================

@Composable
private fun HereKeyBox() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyStore = remember { KeyStore(context) }
    val saved by keyStore.keyFlow.collectAsStateWithLifecycle("")
    var text by remember(saved) { mutableStateOf(saved) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (saved.isBlank()) "Şu an OpenStreetMap kullanılıyor — Türkiye'de " +
                "şubelerin bir kısmını bilmiyor."
            else "HERE bağlı ✓ Samsung'un ve araba navigasyonlarının kullandığı veri.",
            fontSize = 12.sp,
            color = if (saved.isBlank()) DangerRed else OkGreen
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("HERE anahtarı") },
            placeholder = { Text("boş bırakılabilir") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Kaydet", Modifier.weight(1f), {
                scope.launch { keyStore.save(text) }
            })
            if (saved.isNotBlank()) {
                Pill("Sil", Modifier, { scope.launch { keyStore.save("") } }, DangerRed)
            }
        }
        Text(
            "Kayıt bedava ve kredi kartı istemiyor. Günde 1000 sorgu hakkın var, " +
                "sen 20 tanesini bile kullanmazsın.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
        )
    }
}

// ==================== SUBE LISTESI ====================

@Composable
private fun BranchesDialog(place: Place, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val poiStore = remember { PoiStore(context) }
    var reload by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<Pair<Poi, Double>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(reload) {
        loading = true
        val cache = withContext(Dispatchers.IO) { poiStore.load() }
        val here = currentLocation(context)
        val list = brandPois(place.id, cache)
        rows = list.map { poi ->
            poi to (here?.let { (la, ln) -> distanceMeters(la, ln, poi.lat, poi.lng) } ?: -1.0)
        }.sortedBy { if (it.second < 0) Double.MAX_VALUE else it.second }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
        title = { Text("${place.name} şubeleri") },
        text = {
            Column {
                Text(
                    if (loading) "Okunuyor…" else "${rows.size} şube kayıtlı. " +
                        "Yanlış olanı silebilirsin, bir daha eklenmez.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(rows) { (poi, d) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    poi.label.ifBlank { place.name },
                                    fontSize = 13.sp, maxLines = 2
                                )
                                Text(
                                    if (d >= 0) fmtDist(d) else "mesafe bilinmiyor",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    poiStore.blockPoi(place.id, poi)
                                    reload++
                                    onChanged()
                                }
                            }) { Text("Sil", color = DangerRed, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun StatusCard(s: Status) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        when {
            s.checking -> Text("Kontrol ediliyor…", fontSize = 13.sp)
            s.fenceCount == 0 -> {
                Text("Hiçbir nokta izlenmiyor", fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = DangerRed)
                Text("Yerlere uygulama seçmen lazım.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline)
            }
            else -> {
                Text("${s.fenceCount} nokta izleniyor ✓", fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold)
                if (s.nearestMeters >= 0) {
                    Text("En yakın: ${s.nearestName.ifBlank { "-" }} · ${fmtDist(s.nearestMeters)}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                } else {
                    Text("Konum alınamadı", fontSize = 13.sp, color = DangerRed)
                }
                if (s.lastScan > 0) {
                    val t = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                        .format(Date(s.lastScan))
                    Text("Şubeler son güncelleme: $t", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

// ==================== YER SATIRI ====================

private fun fmtDist(d: Double): String =
    if (d > 1200) "%.1f km".format(d / 1000) else "${d.roundToInt()} m"

@Composable
private fun PlaceRow(
    place: Place,
    info: PlaceInfo?,
    expanded: Boolean,
    onTap: () -> Unit,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onSetup: () -> Unit,
    onDelete: () -> Unit,
    onBranches: () -> Unit,
    onAddHere: () -> Unit,
    onSweep: () -> Unit
) {
    val icon = rememberAppIcon(place.targetPackage)
    val accent = icon?.accent ?: MaterialTheme.colorScheme.primary
    var confirmDelete by remember { mutableStateOf(false) }

    val sub = when {
        !place.isReady -> "uygulama seçilmedi"
        place.kind == PlaceKind.BRAND -> "${info?.count ?: 0} şube"
        else -> place.targetLabel
    }
    val dist = info?.nearest?.let { fmtDist(it) } ?: "—"

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            // Renk soldan iceri siziyor, saga dogru kayboluyor
            .background(
                Brush.horizontalGradient(
                    0.0f to accent.copy(alpha = if (place.enabled) 0.34f else 0.10f),
                    0.62f to Color.Transparent
                )
            )
            .clickable { onTap() }
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(
                    bitmap = icon.image,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                )
            } else {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) { Text(place.emoji, fontSize = 18.sp) }
            }

            Spacer(Modifier.width(13.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    place.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (place.enabled) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.outline
                )
                Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }

            if (expanded) {
                Switch(checked = place.enabled, onCheckedChange = { onToggle() })
            } else {
                Text(
                    dist,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (place.enabled) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.outline
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Pill("Dene", Modifier.weight(1f), onTest)
                Pill(if (place.isReady) "Ayarlar" else "Ayarla", Modifier.weight(1f), onSetup)
                Pill("Sil", Modifier, { confirmDelete = true }, DangerRed)
            }
            if (place.kind == PlaceKind.BRAND && place.isReady) {
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Pill("Şubeler", Modifier.weight(1f), onBranches)
                    Pill("Buradayım", Modifier.weight(1f), onAddHere)
                    Pill("Şehri tara", Modifier.weight(1f), onSweep)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("${place.name} silinsin mi?") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Sil", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") }
            }
        )
    }
}

@Composable
private fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    Text(
        text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 16.dp)
    )
}

// ==================== YER AYARLARI ====================

@Composable
private fun PlaceDialog(
    place: Place,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Place) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Yuklu uygulamalari okumak yavas bir is - arka planda yapiliyor,
    // bu yuzden pencere aninda aciliyor.
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { context.installedLaunchableApps() }
    }

    var kind by remember { mutableStateOf(place.kind) }
    var name by remember { mutableStateOf(place.name) }
    var search by remember { mutableStateOf(place.searchText) }
    var pkg by remember { mutableStateOf(place.targetPackage) }
    var label by remember { mutableStateOf(place.targetLabel) }
    var lat by remember { mutableStateOf(place.lat) }
    var lng by remember { mutableStateOf(place.lng) }
    var trigger by remember { mutableFloatStateOf(place.triggerMeters) }
    var sound by remember { mutableStateOf(place.sound) }
    var vibrate by remember { mutableStateOf(place.vibrate) }
    var cooldown by remember { mutableFloatStateOf(place.cooldownMinutes.toFloat()) }

    var pickingApp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var gotFix by remember { mutableStateOf(place.lat != 0.0) }
    var addressText by remember { mutableStateOf("") }
    var addressHits by remember { mutableStateOf<List<Poi>>(emptyList()) }
    var pickedAddress by remember { mutableStateOf("") }

    val isBrand = kind == PlaceKind.BRAND
    val canSave = !busy && name.isNotBlank() && pkg.isNotEmpty() &&
        if (isBrand) search.isNotBlank() else gotFix

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (isNew) "Yeni yer" else place.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isNew) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !isBrand, onClick = { kind = PlaceKind.FIXED })
                        Text("Tek bir yer")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = isBrand, onClick = { kind = PlaceKind.BRAND })
                        Text("Marka — her şube")
                    }
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("İsim") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isBrand) {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        label = { Text("Marka adı (ör. Starbucks)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Nerede olursan ol yakınındaki şubeler kendiliğinden bulunur.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                    )
                }

                OutlinedButton(onClick = { pickingApp = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (label.isEmpty()) "Açılacak uygulamayı seç" else label)
                }

                if (!isBrand) {
                    OutlinedButton(
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                val loc = currentLocation(context)
                                busy = false
                                if (loc == null) error = "Konum alınamadı."
                                else {
                                    lat = loc.first; lng = loc.second
                                    gotFix = true; pickedAddress = "Su anki konumun"
                                }
                            }
                        },
                        enabled = !busy, modifier = Modifier.fillMaxWidth()
                    ) { Text("Şu an buradayım") }

                    Text("veya adres yaz:", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline)

                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        label = { Text("Adres / yer adı") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            busy = true; error = null; addressHits = emptyList()
                            scope.launch {
                                val hits = MapSearch.geocode(addressText)
                                busy = false
                                if (hits.isEmpty()) error = "Bulunamadı, daha açık yaz."
                                else addressHits = hits
                            }
                        },
                        enabled = !busy && addressText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Aranıyor..." else "Adresi ara") }

                    addressHits.forEach { hit ->
                        Text(
                            hit.label,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                lat = hit.lat; lng = hit.lng
                                gotFix = true
                                pickedAddress = hit.label
                                addressHits = emptyList()
                            }.padding(vertical = 6.dp)
                        )
                    }

                    if (gotFix) {
                        Text(
                            "Konum seçildi ✓ ${pickedAddress.take(50)}",
                            fontSize = 12.sp, color = OkGreen
                        )
                    }
                }

                Text("Tetikleme mesafesi: ${trigger.roundToInt()} m", fontSize = 14.sp)
                Slider(value = trigger, onValueChange = { trigger = it }, valueRange = 20f..300f)
                Text(
                    "Bu kadar yaklaşınca çalışır. Geç kalıyorsa büyüt.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )

                Text(
                    if (cooldown < 1f) "Bekleme süresi: yok (her seferinde çalışır)"
                    else "Bekleme süresi: ${cooldown.roundToInt()} dakika",
                    fontSize = 14.sp
                )
                Slider(value = cooldown, onValueChange = { cooldown = it }, valueRange = 0f..180f)
                Text(
                    "Çalıştıktan sonra bu süre boyunca aynı yerde tekrar çalışmaz. " +
                        "Şok için 5 dk, spor salonu için 120 dk mantıklı.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sound, onCheckedChange = { sound = it })
                    Text("Ses")
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = vibrate, onCheckedChange = { vibrate = it })
                    Text("Titreşim")
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    onSave(
                        place.copy(
                            name = name.trim(), kind = kind, searchText = search.trim(),
                            targetPackage = pkg, targetLabel = label,
                            lat = lat, lng = lng, triggerMeters = trigger,
                            cooldownMinutes = cooldown.roundToInt(),
                            sound = sound, vibrate = vibrate
                        )
                    )
                }
            ) { Text("Kaydet") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Vazgeç") }
        }
    )

    if (pickingApp) {
        AlertDialog(
            onDismissRequest = { pickingApp = false },
            confirmButton = {},
            title = { Text("Uygulama seç") },
            text = {
                if (apps.isEmpty()) {
                    Text("Uygulamalar okunuyor…", color = MaterialTheme.colorScheme.outline)
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.packageName }) { a ->
                        ListItem(
                            headlineContent = { Text(a.label) },
                            modifier = Modifier.clickable {
                                pkg = a.packageName
                                label = a.label
                                if (name.isBlank()) name = a.label
                                if (isBrand && search.isBlank()) search = a.label
                                pickingApp = false
                            }
                        )
                    }
                }
            }
        )
    }
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Pair<Double, Double>? = runCatching {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        ?: client.lastLocation.await()
    loc?.let { it.latitude to it.longitude }
}.getOrNull()
