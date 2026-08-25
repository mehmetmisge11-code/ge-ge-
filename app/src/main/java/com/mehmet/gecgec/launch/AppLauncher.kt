package com.mehmet.gecgec.launch

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
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
import com.mehmet.gecgec.R
import com.mehmet.gecgec.TriggerActivity
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.geo.GeofenceManager.Companion.TAG

/**
 * Tetiklenince UC sey birden olur - biri engellenirse digeri kesin gecer:
 *   1. Titresim
 *   2. Ses
 *   3. Bildirim (sesli + titresimli kanal)
 * ve ayrica uygulama acilir.
 */
object AppLauncher {

    private const val PREFS = "gecgec_cooldown"
    private const val CHANNEL_ID = "gecgec_alert_v2"

    fun test(context: Context, place: Place) =
        fire(context, place.copy(cooldownMinutes = 0), "test", place.id)

    /**
     * @param key cooldown anahtari. Markalarda her sube ayri sayilsin diye
     *            yer kimligi degil cember kimligi kullanilir.
     */
    fun fire(context: Context, place: Place, why: String, key: String = place.id) {
        if (isCoolingDown(context, place, key)) {
            EventLog.add(
                context,
                "${place.name}: son ${place.cooldownMinutes} dk icinde calisti, atlandi"
            )
            return
        }
        markTriggered(context, key)

        // 1 + 2: uygulama acilamasa bile bunlar her zaman calisir
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
            notify(context, place, "Uygulama bulunamadi", null)
            return
        }

        // 3: bildirim her halukarda gider
        notify(context, place, why, launchIntent)

        if (!Settings.canDrawOverlays(context)) {
            EventLog.add(context, "${place.name}: bildirim gonderildi ($why)")
            return
        }

        val km = context.getSystemService(KeyguardManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val locked = km?.isKeyguardLocked == true
        val screenOff = pm?.isInteractive == false

        try {
            if (locked || screenOff) {
                val i = Intent(context, TriggerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(TriggerActivity.EXTRA_PKG, place.targetPackage)
                    putExtra(TriggerActivity.EXTRA_LABEL, place.targetLabel)
                    putExtra(TriggerActivity.EXTRA_PLACE, place.name)
                    putExtra(TriggerActivity.EXTRA_EMOJI, place.emoji)
                }
                context.startActivity(i)
                EventLog.add(context, "${place.name}: ekran yakildi ($why)")
            } else {
                context.startActivity(launchIntent)
                EventLog.add(context, "${place.name} → ${place.targetLabel} acildi ($why)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Acma basarisiz", t)
            EventLog.add(context, "${place.name}: acilamadi, bildirim gonderildi")
        }
    }

    // ---- Uyari ----

    private fun vibrate(context: Context) {
        runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            val pattern = longArrayOf(0, 450, 180, 450, 180, 750)
            v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun beep(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        }
    }

    // ---- Bildirim ----

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            CHANNEL_ID,
            "Yer uyarilari",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Kayitli bir yere yaklasinca calan uyari"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 450, 180, 450)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        nm.createNotificationChannel(ch)
    }

    private fun notify(context: Context, place: Place, why: String, launchIntent: Intent?) {
        ensureChannel(context)

        val pi = launchIntent?.let {
            PendingIntent.getActivity(
                context, place.id.hashCode(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${place.emoji} ${place.name}")
            .setContentText(
                if (launchIntent != null) "${place.targetLabel} · açmak için dokun" else why
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        if (pi != null) b.setContentIntent(pi)

        try {
            NotificationManagerCompat.from(context).notify(place.id.hashCode(), b.build())
        } catch (_: SecurityException) {
            Log.e(TAG, "Bildirim izni yok")
        }
    }

    // ---- Cooldown ----

    private fun isCoolingDown(context: Context, place: Place, key: String): Boolean {
        if (place.cooldownMinutes <= 0) return false
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
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
