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
    val openSettings = rememberLauncherForActivi
