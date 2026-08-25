package com.mehmet.gecgec

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehmet.gecgec.data.EventLog

/**
 * Telefon kilitliyken / ekran kapaliyken calisan ekran.
 *
 * Android hicbir uygulamanin kilit ekranini asip baska bir uygulamayi one
 * getirmesine izin vermez. Yapabilecegimiz en iyi sey:
 *   1. Ekrani yakmak
 *   2. Kilit ekraninin UZERINDE bu karti gostermek
 *   3. Sistemin kendi kilit acma akisini cagirmak (parmak izi + yuz ayni anda aktif)
 *   4. Kilit acilir acilmaz hedef uygulamayi acmak
 */
class TriggerActivity : ComponentActivity() {

    private var opened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ekrani yak ve kilit ekraninin uzerinde goster
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val appLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val placeName = intent.getStringExtra(EXTRA_PLACE).orEmpty()
        val emoji = intent.getStringExtra(EXTRA_EMOJI).orEmpty().ifBlank { "📍" }

        setContent {
            MaterialTheme(colorScheme = GecGecDark) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val icon = appIconBitmap(pkg)
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = null,
                                modifier = Modifier.size(96.dp)
                            )
                        } else {
                            Text(emoji, fontSize = 64.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            placeName,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(appLabel, fontSize = 18.sp, textAlign = TextAlign.Center)

                        Spacer(Modifier.height(40.dp))

                        Button(
                            onClick = { unlockThenOpen(pkg) },
                            modifier = Modifier.fillMaxWidth().height(64.dp)
                        ) { Text("Aç", fontSize = 20.sp) }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Parmak izi veya yüz — hangisi hızlıysa",
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Spacer(Modifier.height(24.dp))
                        TextButton(onClick = { finish() }) { Text("Şimdi değil") }
                    }
                }
            }
        }

        // Ekran acilir acilmaz kilit acma akisini kendiliginden baslat
        unlockThenOpen(pkg)

        // Kimse ilgilenmezse cebinde acik kalmasin
        Handler(Looper.getMainLooper()).postDelayed({ if (!opened) finish() }, 90_000)
    }

    private fun unlockThenOpen(pkg: String) {
        if (opened) return
        val km = getSystemService(KeyguardManager::class.java)

        if (km == null || !km.isKeyguardLocked) {
            open(pkg)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open(pkg)
                override fun onDismissError() {
                    EventLog.add(this@TriggerActivity, "Kilit açma hatası")
                }
                override fun onDismissCancelled() {}
            })
        }
    }

    private fun open(pkg: String) {
        if (opened) return
        opened = true
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i)
        } else {
            EventLog.add(this, "$pkg bulunamadı")
        }
        finish()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val EXTRA_PLACE = "place"
        const val EXTRA_EMOJI = "emoji"
    }
}
