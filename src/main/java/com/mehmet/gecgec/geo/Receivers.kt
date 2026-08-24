package com.mehmet.gecgec.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.launch.AppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Geofence tetiklendiğinde sistem burayı çağırır. */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return

        if (event.hasError()) {
            Log.e(
                GeofenceManager.TAG,
                "Geofence hatası: " + GeofenceStatusCodes.getStatusCodeString(event.errorCode)
            )
            return
        }

        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (ids.isEmpty()) return

        // onReceive ~10 sn'de dönmek zorunda; goAsync() ile biraz nefes alıyoruz.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val places = PlaceStore(context).load()
                for (id in ids) {
                    val place = places.firstOrNull { it.id == id && it.enabled } ?: continue
                    AppLauncher.trigger(context, place)
                }
            } catch (t: Throwable) {
                Log.e(GeofenceManager.TAG, "Tetikleme hatası", t)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Cihaz yeniden başladığında veya uygulama güncellendiğinde geofence'ler silinir.
 * Bu receiver olmadan uygulaman ilk restart'ta sessizce çalışmayı bırakır.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                GeofenceManager(context.applicationContext).sync()
            } finally {
                pending.finish()
            }
        }
    }
}
