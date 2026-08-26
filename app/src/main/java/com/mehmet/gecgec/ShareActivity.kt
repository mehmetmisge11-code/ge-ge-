package com.mehmet.gecgec

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.data.PoiStore
import com.mehmet.gecgec.geo.GeofenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * GOOGLE MAPS'TEN SUBE EKLEME
 *
 * Google Maps'te subeyi bul -> Paylas -> GecGec.
 * Google'in kendi koordinati buraya duser. Bedava, anahtarsiz, milimetrik.
 *
 * Uc bicimi de anlar:
 *   1. https://maps.app.goo.gl/xxxx     (kisa baglanti - takip edilir)
 *   2. https://www.google.com/maps/...  (icinde koordinat var)
 *   3. "36.8969, 30.7133"               (duz koordinat)
 *   4. geo:36.8969,30.7133
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Intent.ACTION_VIEW -> intent.dataString.orEmpty()
            else -> ""
        }

        setContent {
            MaterialTheme(colorScheme = GecGecDark) {
                Surface(Modifier.fillMaxSize()) { Screen(raw) { finish() } }
            }
        }
    }

    @Composable
    private fun Screen(raw: String, onClose: () -> Unit) {
        val scope = rememberCoroutineScope()
        var coords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var places by remember { mutableStateOf<List<Place>>(emptyList()) }
        var done by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(raw) {
            places = PlaceStore(this@ShareActivity).load().filter { it.isReady }
            val c = withContext(Dispatchers.IO) { extractCoords(raw) }
            if (c == null) error = "Bu bağlantıdan konum çıkaramadım."
            else coords = c
        }

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Şube ekle", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            when {
                done != null -> {
                    Text(done!!, fontSize = 15.sp, color = OkGreen)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text("Tamam")
                    }
                }

                error != null -> {
                    Text(error!!, fontSize = 14.sp, color = DangerRed)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Google Maps'te yere uzun bas, alttan Paylaş'a bas ve " +
                            "GeçGeç'i seç. Ya da koordinatı kopyalayıp yolla.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text("Kapat")
                    }
                }

                coords == null -> {
                    Text("Konum okunuyor…", fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                else -> {
                    Text(
                        "Bu konum hangi yere ait?",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(places, key = { it.id }) { p ->
                            ListItem(
                                headlineContent = { Text(p.name) },
                                supportingContent = { Text(p.targetLabel, fontSize = 12.sp) },
                                modifier = Modifier.clickable {
                                    val (la, ln) = coords!!
                                    scope.launch {
                                        val ctx = this@ShareActivity
                                        val ok = PoiStore(ctx)
                                            .addManual(p.id, la, ln, "${p.name} (haritadan)")
                                        if (ok) {
                                            EventLog.add(ctx, "${p.name}: şube haritadan eklendi")
                                            withContext(Dispatchers.IO) {
                                                GeofenceManager(ctx).sync()
                                            }
                                        }
                                        done = if (ok) "${p.name} şubesi eklendi ✓"
                                        else "Burası zaten kayıtlıydı."
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text("Vazgeç")
                    }
                }
            }
        }
    }
}

// ---- Koordinat cikarma ----

private val COORD = Regex("""(-?\d{1,3}\.\d{4,})[,\s]+(-?\d{1,3}\.\d{4,})""")
private val G_3D4D = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
private val G_AT = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
private val URL_RX = Regex("""https?://\S+""")

private fun pick(m: MatchResult?): Pair<Double, Double>? {
    val hit = m ?: return null
    val a = hit.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
    val b = hit.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
    if (a < -90 || a > 90 || b < -180 || b > 180) return null
    return a to b
}

internal fun extractCoords(raw: String): Pair<Double, Double>? {
    if (raw.isBlank()) return null

    // geo:36.89,30.71
    if (raw.startsWith("geo:")) {
        pick(COORD.find(raw))?.let { return it }
    }
    // Metnin icinde duz koordinat
    pick(G_3D4D.find(raw))?.let { return it }
    pick(G_AT.find(raw))?.let { return it }

    // Kisa baglantiyi ac ve gercek adrese bak
    val url = URL_RX.find(raw)?.value?.trimEnd('.', ',', ')')
    if (url != null) {
        val expanded = runCatching { expand(url) }.getOrNull()
        if (expanded != null) {
            pick(G_3D4D.find(expanded))?.let { return it }
            pick(G_AT.find(expanded))?.let { return it }
            pick(COORD.find(expanded))?.let { return it }
        }
    }
    return pick(COORD.find(raw))
}

/** Kisa baglantiyi takip eder; hem son adresi hem sayfa govdesini dondurur. */
private fun expand(url: String): String {
    var current = url
    var body = ""
    repeat(5) {
        val c = (URL(current).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GecGec")
        }
        val code = c.responseCode
        val loc = c.getHeaderField("Location")
        if (code in 300..399 && !loc.isNullOrBlank()) {
            current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
            c.disconnect()
        } else {
            body = runCatching {
                c.inputStream.bufferedReader().use { r -> r.readText().take(200_000) }
            }.getOrDefault("")
            c.disconnect()
            return "$current\n$body"
        }
    }
    return "$current\n$body"
}
