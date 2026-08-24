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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.geo.GeofenceManager
import com.mehmet.gecgec.launch.AppLauncher
import com.mehmet.gecgec.launch.InstalledApp
import com.mehmet.gecgec.launch.installedLaunchableApps
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

// ============================ KÖK ============================

@Composable
private fun Root() {
    val steps = rememberSetupSteps()
    val next = steps.firstOrNull { !it.done }

    if (next != null) SetupScreen(next, steps.count { it.done }, steps.size)
    else HomeScreen()
}

// ============================ KURULUM SİHİRBAZI ============================

private data class SetupStep(
    val title: String,
    val hint: String,
    val buttonText: String,
    val done: Boolean,
    val action: () -> Unit
)

@Composable
private fun rememberSetupSteps(): List<SetupStep> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    // Ayarlardan geri dönünce durumu tazele
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
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
            SetupStep(
                "Konum izni",
                "Nerede olduğunu bilmem lazım.",
                "İzin ver", fine
            ) {
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
                "Açılan listede GeçGeç'i bul, \"Kısıtlama yok\" seç. " +
                    "Yoksa telefon birkaç gün sonra uygulamayı öldürür.",
                "Aç", battery
            ) {
                openSettings.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            SetupStep(
                "Bildirim izni",
                "Bir şey ters giderse haber vereyim diye.",
                "İzin ver", notif
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
        Text("Kurulum · ${doneCount + 1}/$total", color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(20.dp))
        Text(
            step.title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(step.hint, textAlign = TextAlign.Center, fontSize = 16.sp)
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = step.action,
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) { Text(step.buttonText, fontSize = 18.sp) }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { doneCount / total.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ============================ ANA EKRAN ============================

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PlaceStore(context) }
    val geo = remember { GeofenceManager(context) }
    val places by store.placesFlow.collectAsStateWithLifecycle(emptyList())

    var configuring by remember { mutableStateOf<Place?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Yer listesi her değiştiğinde geofence'leri yeniden kur
    LaunchedEffect(places) { if (places.isNotEmpty()) geo.sync(places) }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("GeçGeç", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Oraya varınca uygulama kendi açılır.", color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(6.dp))

        places.forEach { p ->
            PlaceCard(
                place = p,
                onSetup = { configuring = p },
                onToggle = { scope.launch { store.update(p.id) { it.copy(enabled = !it.enabled) } } },
                onTest = { AppLauncher.trigger(context, p.copy(cooldownMinutes = 0)) }
            )
        }

        toast?.let {
            Spacer(Modifier.weight(1f))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
    }

    configuring?.let { place ->
        SetupPlaceDialog(
            place = place,
            onDismiss = { configuring = null },
            onDone = { pkg, label, lat, lng ->
                scope.launch {
                    store.update(place.id) {
                        it.copy(targetPackage = pkg, targetLabel = label, lat = lat, lng = lng)
                    }
                    configuring = null
                    toast = "${place.name} kuruldu ✓"
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
    onTest: () -> Unit
) {
    Card {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(place.emoji, fontSize = 30.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(place.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (place.isReady) "→ ${place.targetLabel}" else "Henüz kurulmadı",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 14.sp
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
                        Text("Değiştir")
                    }
                }
            } else {
                Button(
                    onClick = onSetup,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Buradayken bas", fontSize = 16.sp) }
            }
        }
    }
}

// ============================ YER KURMA ============================

@Composable
private fun SetupPlaceDialog(
    place: Place,
    onDismiss: () -> Unit,
    onDone: (pkg: String, label: String, lat: Double, lng: Double) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember { context.installedLaunchableApps() }

    var picked by remember { mutableStateOf<InstalledApp?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                if (picked == null) "${place.name} için hangi uygulama?"
                else "Şu an ${place.name}'da mısın?"
            )
        },
        text = {
            if (picked == null) {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(apps, key = { it.packageName }) { a ->
                        ListItem(
                            headlineContent = { Text(a.label) },
                            modifier = Modifier.clickable { picked = a }
                        )
                    }
                }
            } else {
                Column {
                    Text("Konumu buradan alacağım. ${place.name} içindeysen devam et.")
                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (picked != null) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val loc = currentLocation(context)
                            busy = false
                            if (loc == null) {
                                error = "Konum alınamadı, tekrar dene."
                            } else {
                                onDone(picked!!.packageName, picked!!.label, loc.first, loc.second)
                            }
                        }
                    }
                ) { Text(if (busy) "Konum alınıyor…" else "Buradayım, kaydet") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Vazgeç") }
        }
    )
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Pair<Double, Double>? = runCatching {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        ?: client.lastLocation.await()
    loc?.let { it.latitude to it.longitude }
}.getOrNull()
