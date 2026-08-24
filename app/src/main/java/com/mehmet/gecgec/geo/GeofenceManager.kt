package com.mehmet.gecgec.geo

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.data.PlaceStore
import kotlinx.coroutines.tasks.await

/**
 * Geofence'leri kurar/söker.
 *
 * Bilinmesi gerekenler:
 *  - Cihaz yeniden başlayınca TÜM geofence'ler silinir -> BootReceiver yeniden kurar.
 *  - Kullanıcı konumu kapatıp açarsa da düşebilir -> uygulama her açıldığında sync() çağrılır.
 */
class GeofenceManager(private val context: Context) {

    private val client = LocationServices.getGeofencingClient(context)

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        // MUTABLE zorunlu: sistem tetikleme verisini bu intent'in içine yazıyor.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun hasPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        return fine && bg
    }

    @SuppressLint("MissingPermission")
    suspend fun sync(places: List<Place>? = null): Result<Int> = runCatching {
        if (!hasPermissions()) error("Konum izni eksik")

        val list = places ?: PlaceStore(context).load()
        runCatching { client.removeGeofences(pendingIntent()).await() }

        val active = list.filter { it.enabled && it.isReady }
        if (active.isEmpty()) return@runCatching 0

        val fences = active.map { p ->
            Geofence.Builder()
                .setRequestId(p.id)
                .setCircularRegion(p.lat, p.lng, p.radiusMeters)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(p.dwellSeconds * 1000)
                .setNotificationResponsiveness(0)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofences(fences)
            .build()

        client.addGeofences(request, pendingIntent()).await()
        Log.i(TAG, "${fences.size} geofence kuruldu")
        fences.size
    }

    companion object {
        const val TAG = "GecGec"
    }
}
