package com.mehmet.gecgec

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.geo.GeofenceManager
import com.mehmet.gecgec.launch.AppLauncher
import com.mehmet.gecgec.launch.InstalledApp
import com.mehmet.gecgec.launch.installedLaunchableApps
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root() }
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
            SetupStep(
                "Bildirim izni", "Uyari gonderebilmem icin.",
                "Izin ver", notif
            ) {
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
        Text(
            step.title, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
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

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PlaceStore(context) }
    val geo = remember { GeofenceManager(context) }
    val places by store.placesFlow.collectAsStateWithLifecycle(emptyList())

    var editing by remember { mutableStateOf<Place?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var logTick by remember { mutableIntStateOf(0) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) logTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) { store.ensureSeeded() }
    LaunchedEffect(places) { if (places.isNotEmpty()) geo.sync(places) }

    val log = remember(logTick) { EventLog.read(context) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Spacer(Modifier.height(20.dp))
                Text("GecGec", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${places.count { it.isReady && it.enabled }} yer izleniyor",
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        items(places, key = { it.id }) { p ->
            PlaceCard(
                place = p,
                onSetup = { editing = p; editingIsNew = false },
                onToggle = {
                    scope.launch { store.update(p.id) { it.copy(enabled = !it.enabled) } }
                },
                onTest = { AppLauncher.test(context, p); logTick++ },
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

        item {
            Column {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Olan biten", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { EventLog.clear(context); logTick++ }) {
                        Text("Temizle")
                    }
                }
                Text(
                    "Bir yere yaklastiginda burada satir cikar. Cikmiyorsa telefon " +
                        "uygulamayi durdurmustur.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        if (log.isEmpty()) {
            item {
                Text(
                    "Henuz kayit yok.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        items(log) { line ->
            Text(
                line,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                    logTick++
                }
            }
        )
    }
}

@Composable
private fun PlaceCard(
    place: Place,
    onSetup: () -> Unit,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(place.emoji, fontSize = 30.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(place.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (place.isReady)
                            "${place.targetLabel} · ${place.triggerMeters.roundToInt()} m"
                        else "Henuz kurulmadi",
                        color = MaterialTheme.colorScheme.outline, fontSize = 14.sp
                    )
                }
                if (place.isReady) {
                    Switch(checked = place.enabled, onCheckedChange = { onToggle() })
                }
            }

            Spacer(Modifier.height(12.dp))

            if (place.isReady) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f)) {
                        Text("Dene")
                    }
                    OutlinedButton(onClick = onSetup, modifier = Modifier.weight(1f)) {
                        Text("Degistir")
                    }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Sil", color = Color(0xFFC62828))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSetup,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("Buradayken bas", fontSize = 16.sp) }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Sil", color = Color(0xFFC62828))
                    }
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

// ==================== YER KURMA / DUZENLEME ====================

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

    var name by remember { mutableStateOf(place.name) }
    var pkg by remember { mutableStateOf(place.targetPackage) }
    var label by remember { mutableStateOf(place.targetLabel) }
    var lat by remember { mutableStateOf(place.lat) }
    var lng by remember { mutableStateOf(place.lng) }
    var trigger by remember { mutableFloatStateOf(place.triggerMeters) }
    var sound by remember { mutableStateOf(place.sound) }
    var vibrate by remember { mutableStateOf(place.vibrate) }

    var pickingApp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var gotFix by remember { mutableStateOf(place.lat != 0.0) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (isNew) "Yeni yer" else place.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isNew) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Isim (or. Sok - Bahcelievler)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedButton(
                    onClick = { pickingApp = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (label.isEmpty()) "Acilacak uygulamayi sec" else label) }

                OutlinedButton(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            val loc = currentLocation(context)
                            busy = false
                            if (loc == null) error = "Konum alinamadi, tekrar dene."
                            else { lat = loc.first; lng = loc.second; gotFix = true }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            busy -> "Konum aliniyor..."
                            gotFix -> "Konum kayitli - yenilemek icin bas"
                            else -> "Buradayim, konumu al"
                        }
                    )
                }

                Text("Tetikleme mesafesi: ${trigger.roundToInt()} m", fontSize = 14.sp)
                Slider(
                    value = trigger,
                    onValueChange = { trigger = it },
                    valueRange = 20f..200f
                )
                Text(
                    "GPS hassasiyeti ~10 m. 20'de birakabilirsin ama bina icinde " +
                        "geciktigini gorursen 50'ye cikar.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sound, onCheckedChange = { sound = it })
                    Text("Ses cikar")
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = vibrate, onCheckedChange = { vibrate = it })
                    Text("Titret")
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && gotFix && pkg.isNotEmpty() && name.isNotBlank(),
                onClick = {
                    onSave(
                        place.copy(
                            name = name.trim(),
                            targetPackage = pkg,
                            targetLabel = label,
                            lat = lat,
                            lng = lng,
                            triggerMeters = trigger,
                            sound = sound,
                            vibrate = vibrate
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
