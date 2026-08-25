package com.mehmet.gecgec

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.mehmet.gecgec.data.MapSearch
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.data.PlaceKind
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.data.Poi
import com.mehmet.gecgec.data.PoiStore
import com.mehmet.gecgec.data.buildTargets
import com.mehmet.gecgec.data.distanceMeters
import com.mehmet.gecgec.geo.GeofenceManager
import com.mehmet.gecgec.launch.AppLauncher
import com.mehmet.gecgec.launch.installedLaunchableApps
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Hedef uygulamanin kendi ikonunu getirir - Sok'un amblemi, Starbucks'in amblemi. */
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    SplashGate { Root() }
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val steps = rememberSetupSteps()
    val next = steps.firstOrNull { !it.done }
    if (next != null) SetupScreen(next, steps.count { it.done }, steps.size)
    else HomeScreen()
}

// ==================== KURULUM SIHIRBAZI ====================

private data class SetupStep(
    val title: String, val hint: String, val buttonText: String,
    val done: Boolean, val action: () -> Unit
)

@Composable
private fun rememberSetupSteps(): List<SetupStep> {
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

    return remember(tick) {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)
        val overlay = Settings.canDrawOverlays(context)
        val battery = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

        listOf(
            SetupStep("Konum izni", "Nerede oldugunu bilmem lazim.", "Izin ver", fine) {
                askFine.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            SetupStep(
                "\"Her zaman izin ver\"",
                "Acilan ekranda konum icin \"Her zaman izin ver\"i sec. " +
                    "Bu olmadan telefon cebindeyken calismaz.",
                "Ac", bg
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    askBg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            SetupStep(
                "Ustte gosterme izni",
                "Acilan listede GecGec'i bul ve ac. " +
                    "Android uygulamayi ancak bu izinle kendiliginden actiriyor.",
                "Ac", overlay
            ) {
                openSettings.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            },
            SetupStep(
                "Pili kisitlama",
                "Listenin ustundeki menuden \"Tumu\"nu sec, GecGec'i bul, anahtari KAPAT.",
                "Ac", battery
            ) {
                openSettings.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            SetupStep("Bildirim izni", "Uyari gonderebilmem icin.", "Izin ver", notif) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )
    }
}

@Composable
private fun SetupScreen(step: SetupStep, doneCount: Int, total: Int) {
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
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { doneCount / total.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ==================== ANA EKRAN ====================

/** Her yer icin karta yazilan ozet: kac sube var, en yakini kac metre. */
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
    var tick by remember { mutableIntStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(Status()) }
    var info by remember { mutableStateOf<Map<String, PlaceInfo>>(emptyMap()) }
    var refreshing by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) { store.ensureSeeded() }

    // Yerler degistiginde cemberleri kur; gerekiyorsa subeleri de kendiliginden yeniler
    LaunchedEffect(places) { if (places.isNotEmpty()) geo.sync(places) }

    // Durum: kac nokta izleniyor, en yakini kac metre
    LaunchedEffect(places, tick) {
        status = status.copy(checking = true)
        val cache = poiStore.load()
        val targets = buildTargets(places, cache)
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
                nearest = here?.let { (la, ln) ->
                    list.minOf { distanceMeters(la, ln, it.lat, it.lng) }
                }
            )
        }
    }

    val log = remember(tick, showLog) { EventLog.read(context) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                geo.sync(places, forceRefresh = true)
                tick++
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Spacer(Modifier.height(20.dp))
                Text("GecGec", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }

        items(places, key = { it.id }) { p ->
            PlaceCard(
                place = p,
                info = info[p.id],
                onSetup = { editing = p; editingIsNew = false },
                onToggle = {
                    scope.launch { store.update(p.id) { it.copy(enabled = !it.enabled) } }
                },
                onTest = { AppLauncher.test(context, p); tick++ },
                onDelete = { scope.launch { store.delete(p.id) } }
            )
        }

        item {
            OutlinedButton(
                onClick = {
                    editing = Place(name = "", emoji = "📍")
                    editingIsNew = true
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("+ Yeni yer ekle", fontSize = 16.sp) }
        }

        // Kayitlar - normalde kapali, tek satir yer kaplar
        item {
            Row(
                Modifier.fillMaxWidth().clickable { showLog = !showLog }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (showLog) "▾" else "▸", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Kayitlar",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.weight(1f))
                if (showLog) {
                    TextButton(onClick = { EventLog.clear(context); tick++ }) { Text("Temizle") }
                }
            }
        }

        if (showLog) {
            item { StatusCard(status) }
            if (log.isEmpty()) {
                item {
                    Text("Henuz kayit yok.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            items(log) { line ->
                Text(
                    line,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    }

    editing?.let { place ->
        PlaceDialog(
            place = place,
            isNew = editingIsNew,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch {
                    if (editingIsNew) store.add(updated)
                    else store.update(updated.id) { updated }
                    editing = null
                    tick++
                }
            }
        )
    }
}

@Composable
private fun StatusCard(s: Status) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.checking) {
                Text("Kontrol ediliyor...", fontSize = 14.sp)
                return@Column
            }

            if (s.fenceCount == 0) {
                Text("Hicbir nokta izlenmiyor", fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, color = Color(0xFFC62828))
                Text(
                    "Asagidaki yerlere uygulama secmen lazim.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.outline
                )
                return@Column
            }

            Text("${s.fenceCount} nokta izleniyor ✓", fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)

            if (s.nearestMeters >= 0) {
                val m = s.nearestMeters.roundToInt()
                val txt = if (m > 1200) "%.1f km".format(s.nearestMeters / 1000) else "$m m"
                Text("En yakin: ${s.nearestName.ifBlank { "-" }} · $txt", fontSize = 14.sp)
                if (s.nearestMeters <= s.nearestTrigger) {
                    Text("Tetikleme mesafesindesin - calismasi gerekirdi",
                        fontSize = 13.sp, color = Color(0xFFC62828))
                }
            } else {
                Text("Konum alinamadi", fontSize = 14.sp, color = Color(0xFFC62828))
            }

            if (s.lastScan > 0) {
                val t = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(s.lastScan))
                Text("Subeler son guncelleme: $t", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** "8 sube · en yakin 340 m" gibi. Tetikleme mesafesi burada gosterilmez. */
private fun subtitleFor(place: Place, info: PlaceInfo?): String {
    if (!place.isReady) return "Uygulama secilmedi"

    val d = info?.nearest
    val dist = when {
        d == null -> null
        d > 1200 -> "en yakin %.1f km".format(d / 1000)
        else -> "en yakin ${d.roundToInt()} m"
    }

    return if (place.kind == PlaceKind.BRAND) {
        val n = info?.count ?: 0
        listOfNotNull(
            if (n > 0) "$n sube" else "sube araniyor",
            dist
        ).joinToString(" · ")
    } else {
        listOfNotNull(place.targetLabel, dist).joinToString(" · ")
    }
}

@Composable
private fun PlaceCard(
    place: Place,
    info: PlaceInfo?,
    onSetup: () -> Unit,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val isBrand = place.kind == PlaceKind.BRAND
    val context = LocalContext.current
    val icon = remember(place.targetPackage) { context.appIconBitmap(place.targetPackage) }

    Card {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(44.dp))
                } else {
                    Text(place.emoji, fontSize = 30.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(place.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitleFor(place, info),
                        color = MaterialTheme.colorScheme.outline, fontSize = 14.sp
                    )
                }
                if (place.isReady) {
                    Switch(checked = place.enabled, onCheckedChange = { onToggle() })
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (place.isReady) {
                    OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f)) {
                        Text("Dene")
                    }
                    OutlinedButton(onClick = onSetup, modifier = Modifier.weight(1f)) {
                        Text("Ayarlar")
                    }
                } else {
                    Button(
                        onClick = onSetup,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("Ayarla", fontSize = 16.sp) }
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Sil", color = Color(0xFFC62828))
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
                    Text("Sil", color = Color(0xFFC62828))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Vazgec") }
            }
        )
    }
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
    val apps = remember { context.installedLaunchableApps() }

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
                        Text("Marka - her sube")
                    }
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Isim") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isBrand) {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        label = { Text("Marka adi (or. Starbucks)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Nerede olursan ol yakinindaki subeler kendiliginden bulunur.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                    )
                }

                OutlinedButton(onClick = { pickingApp = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (label.isEmpty()) "Acilacak uygulamayi sec" else label)
                }

                if (!isBrand) {
                    OutlinedButton(
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                val loc = currentLocation(context)
                                busy = false
                                if (loc == null) error = "Konum alinamadi."
                                else {
                                    lat = loc.first; lng = loc.second
                                    gotFix = true; pickedAddress = "Su anki konumun"
                                }
                            }
                        },
                        enabled = !busy, modifier = Modifier.fillMaxWidth()
                    ) { Text("Su an buradayim") }

                    Text("veya adres yaz:", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline)

                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        label = { Text("Adres / yer adi") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            busy = true; error = null; addressHits = emptyList()
                            scope.launch {
                                val hits = MapSearch.geocode(addressText)
                                busy = false
                                if (hits.isEmpty()) error = "Bulunamadi, daha acik yaz."
                                else addressHits = hits
                            }
                        },
                        enabled = !busy && addressText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Araniyor..." else "Adresi ara") }

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
                            "Konum secildi ✓ ${pickedAddress.take(50)}",
                            fontSize = 12.sp, color = Color(0xFF2E7D32)
                        )
                    }
                }

                Text("Tetikleme mesafesi: ${trigger.roundToInt()} m", fontSize = 14.sp)
                Slider(value = trigger, onValueChange = { trigger = it }, valueRange = 20f..300f)
                Text(
                    "Bu kadar yaklasinca calisir. Gec kaliyorsa buyut.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )

                Text(
                    if (cooldown < 1f) "Bekleme suresi: yok (her seferinde calisir)"
                    else "Bekleme suresi: ${cooldown.roundToInt()} dakika",
                    fontSize = 14.sp
                )
                Slider(value = cooldown, onValueChange = { cooldown = it }, valueRange = 0f..180f)
                Text(
                    "Calistiktan sonra bu sure boyunca ayni yerde tekrar calismaz. " +
                        "Sok icin 5 dk, spor salonu icin 120 dk mantikli.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sound, onCheckedChange = { sound = it })
                    Text("Ses")
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = vibrate, onCheckedChange = { vibrate = it })
                    Text("Titresim")
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
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Vazgec") }
        }
    )

    if (pickingApp) {
        AlertDialog(
            onDismissRequest = { pickingApp = false },
            confirmButton = {},
            title = { Text("Uygulama sec") },
            text = {
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
