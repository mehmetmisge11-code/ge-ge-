package com.mehmet.gecgec.launch

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mehmet.gecgec.TriggerActivity
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.geo.GeofenceManager.Companion.TAG

/**
 * Tetiklenince: titret + sesle uyar + uygulamayi ac.
 *
 * Android 10'dan beri arka plandaki uygulama kendi basina activity baslatamaz.
 * Tek pratik muafiyet: SYSTEM_ALERT_WINDOW ("Diger uygulamalarin uzerinde goster").
 * O izin yoksa "dokun ve ac" bildirimine duseriz.
 */
object AppLauncher {

    private const val PREFS = "gecgec_cooldown"
    private const val CHANNEL_ID = "gecgec_launch"

    /** Test butonu icin: cooldown'u atlayarak calistirir. */
    fun test(context: Context, place: Place) =
        fire(context, place.copy(cooldownMinutes = 0), "test", place.id)

    /**
     * @param key cooldown anahtari. Markalarda her sube ayri sayilsin diye
     *            yer kimligi degil cember kimligi kullanilir.
     */
    fun fire(context: Context, place: Place, why: String, key: String = place.id) {
        if (isCoolingDown(context, place, key)) {
            EventLog.add(context, "${place.name}: yakinda zaten calisti, atlandi")
            return
        }
        markTriggered(context, key)

        if (place.vibrate) vibrate(context)
        if (place.sound) beep(context)

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(place.targetPackage)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }

        if (launchIntent == null) {
            EventLog.add(context, "${place.targetLabel} bulunamadi (kaldirilmis olabilir)")
            return
        }

        if (Settings.canDrawOverlays(context)) {
            val km = context.getSystemService(KeyguardManager::class.java)
            val pm = context.getSystemService(PowerManager::class.java)
            val locked = km?.isKeyguardLocked == true
            val screenOff = pm?.isInteractive == false

            try {
                if (locked || screenOff) {
                    // Kilitli/ekran kapali: ekrani yakip kilit ustunde kart goster,
                    // parmak izi veya yuz ile acilinca hedef uygulama one gelir.
                    val i = Intent(context, TriggerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(TriggerActivity.EXTRA_PKG, place.targetPackage)
                        putExtra(TriggerActivity.EXTRA_LABEL, place.targetLabel)
                        putExtra(TriggerActivity.EXTRA_PLACE, place.name)
                        putExtra(TriggerActivity.EXTRA_EMOJI, place.emoji)
                    }
                    context.startActivity(i)
                    EventLog.add(context, "${place.name}: ekran yakildi, kilit bekleniyor ($why)")
                } else {
                    context.startActivity(launchIntent)
                    EventLog.add(context, "${place.name} → ${place.targetLabel} acildi ($why)")
                }
                Log.i(TAG, "${place.targetLabel} tetiklendi")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "Dogrudan acma basarisiz", t)
            }
        }

        EventLog.add(context, "${place.name}: bildirim gonderildi ($why) - ustte gosterme izni yok")
        notifyTapToOpen(context, place, launchIntent)
    }

    // ---- Uyari ----

    private fun vibrate(context: Context) = runCatching {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        val pattern = longArrayOf(0, 400, 200, 400, 200, 700)
        v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun beep(context: Context) = runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(context, uri)?.play()
    }

    // ---- Bildirim yedegi ----

    private fun notifyTapToOpen(context: Context, place: Place, launchIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Konum tetikleyicileri",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
        }

        val pi = PendingIntent.getActivity(
            context, place.id.hashCode(), launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("${place.name} - ${place.targetLabel}")
            .setContentText("Acmak icin dokun")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(place.id.hashCode(), notif)
        } catch (_: SecurityException) {
            Log.e(TAG, "Bildirim izni yok")
        }
    }

    // ---- Cooldown ----

    private fun isCoolingDown(context: Context, place: Place, key: String): Boolean {
        if (place.cooldownMinutes <= 0) return false
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(key, 0L)
        return System.currentTimeMillis() - last < place.cooldownMinutes * 60_000L
    }

    private fun markTriggered(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key, System.currentTimeMillis()).apply()
    }
}

data class InstalledApp(val packageName: String, val label: String)

fun Context.installedLaunchableApps(): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val pm = packageManager
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
