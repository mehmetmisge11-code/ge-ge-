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
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.data.PlaceStore
import kotlinx.coroutines.tasks.await

/**
 * Dis cemberi kurar. Bu cember sadece "yaklastin" demek icin -
 * asil 20 metre olcumu ProximityService icinde GPS ile yapiliyor.
 */
class GeofenceManager(private val context: Context) {

    private val client = LocationServices.getGeofencingClient(context)

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
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
        if (!hasPermissions()) {
            EventLog.add(context, "Konum izni eksik - cember kurulamadi")
            error("Konum izni eksik")
        }

        val list = places ?: PlaceStore(context).load()
        runCatching { client.removeGeofences(pendingIntent()).await() }

        val active = list.filter { it.enabled && it.isReady }
        if (active.isEmpty()) {
            EventLog.add(context, "Kurulu yer yok")
            return@runCatching 0
        }

        val fences = active.map { p ->
            Geofence.Builder()
                .setRequestId(p.id)
                .setCircularRegion(p.lat, p.lng, p.fenceMeters)
                // ENTER: cembere girer girmez uyan. DWELL beklemek gec kaliyordu.
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .setNotificationResponsiveness(0)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        }

        val request = GeofencingRequest.Builder()
            // Zaten cemberin icindeysen de tetiklensin
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences)
            .build()

        client.addGeofences(request, pendingIntent()).await()
        EventLog.add(context, "${fences.size} yer izlemeye alindi")
        Log.i(TAG, "${fences.size} geofence kuruldu")
        fences.size
    }.onFailure {
        EventLog.add(context, "Cember kurulamadi: ${it.message}")
    }

    companion object {
        const val TAG = "GecGec"
    }
}
