package com.mehmet.gecgec.launch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.geo.GeofenceManager.Companion.TAG

/**
 * İşin can alıcı noktası burası.
 *
 * Android 10'dan beri arka plandaki bir uygulama kendi başına activity başlatamaz.
 * Muafiyetler (developer.android.com/guide/components/activities/background-starts):
 *   - Uygulamanın görünür bir penceresi var
 *   - SYSTEM_ALERT_WINDOW ("Diğer uygulamaların üzerinde göster") izni verilmiş   <-- bizim yolumuz
 *   - Sistemin gönderdiği bir PendingIntent'ten başlatılıyor (ör. bildirime dokunma)
 *   - START_ACTIVITIES_FROM_BACKGROUND (sadece sistem uygulamaları)
 *
 * Yani: overlay izni varsa gerçekten OTOMATİK açılır.
 * Yoksa en fazla "dokun ve aç" bildirimi gösterebiliriz — bunu fallback olarak yapıyoruz.
 */
object AppLauncher {

    private const val PREFS = "gecgec_cooldown"
    private const val CHANNEL_ID = "gecgec_launch"

    fun canLaunchFromBackground(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun trigger(context: Context, place: Place) {
        if (isCoolingDown(context, place)) {
            Log.i(TAG, "${place.name}: cooldown içinde, atlandı")
            return
        }

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(place.targetPackage)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }

        if (launchIntent == null) {
            Log.e(TAG, "${place.targetPackage} bulunamadı (kaldırılmış olabilir)")
            return
        }

        markTriggered(context, place)

        if (canLaunchFromBackground(context)) {
            try {
                context.startActivity(launchIntent)
                Log.i(TAG, "${place.targetLabel} açıldı (${place.name})")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "Doğrudan açma başarısız, bildirime düşülüyor", t)
            }
        }

        notifyTapToOpen(context, place, launchIntent)
    }

    // ---- Fallback: yüksek öncelikli bildirim ----

    private fun notifyTapToOpen(context: Context, place: Place, launchIntent: Intent) {
        ensureChannel(context)

        val pi = PendingIntent.getActivity(
            context,
            place.id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("${place.name} — ${place.targetLabel}")
            .setContentText("Açmak için dokun")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(place.id.hashCode(), notif)
        } catch (_: SecurityException) {
            Log.e(TAG, "POST_NOTIFICATIONS izni yok")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Konum tetikleyicileri",
            NotificationManager.IMPORTANCE_HIGH
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    // ---- Cooldown ----

    private fun isCoolingDown(context: Context, place: Place): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(place.id, 0L)
        return System.currentTimeMillis() - last < place.cooldownMinutes * 60_000L
    }

    private fun markTriggered(context: Context, place: Place) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(place.id, System.currentTimeMillis())
            .apply()
    }
}

/** Yüklü, başlatılabilir uygulamaları listeler (manifest'teki <queries> bloğu sayesinde). */
data class InstalledApp(val packageName: String, val label: String)

fun Context.installedLaunchableApps(): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val pm = packageManager
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
