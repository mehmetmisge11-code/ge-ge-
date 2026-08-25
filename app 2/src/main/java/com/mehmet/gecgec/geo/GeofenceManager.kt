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
import com.google.android.gms.location.Priority
import com.mehmet.gecgec.data.AREA_FENCE_ID
import com.mehmet.gecgec.data.EventLog
import com.mehmet.gecgec.data.Place
import com.mehmet.gecgec.data.PlaceKind
import com.mehmet.gecgec.data.PlaceStore
import com.mehmet.gecgec.data.PoiStore
import com.mehmet.gecgec.data.buildTargets
import kotlinx.coroutines.tasks.await

/**
 * Cemberleri kurar.
 *
 * Iki tur cember var:
 *  - Yer cemberleri (150 m): girince ProximityService uyanir, 20 metreyi o olcer.
 *  - Bolge cemberi (4 km): buradan CIKINCA yakindaki subeler haritadan yeniden cekilir.
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
    private suspend fun currentLocation(): Pair<Double, Double>? = runCatching {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val loc = fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            ?: fused.lastLocation.await()
        loc?.let { it.latitude to it.longitude }
    }.getOrNull()

    /**
     * @param forceRefresh true ise subeler her halukarda yeniden cekilir.
     */
    @SuppressLint("MissingPermission")
    suspend fun sync(
        places: List<Place>? = null,
        forceRefresh: Boolean = false
    ): Result<Int> = runCatching {
        if (!hasPermissions()) {
            EventLog.add(context, "Konum izni eksik - cember kurulamadi")
            error("Konum izni eksik")
        }

        val list = places ?: PlaceStore(context).load()
        val poiStore = PoiStore(context)
        var cache = poiStore.load()

        val hasBrand = list.any { it.enabled && it.kind == PlaceKind.BRAND && it.isReady }
        if (hasBrand) {
            val here = currentLocation()
            if (here != null && (forceRefresh || poiStore.isStale(cache, here.first, here.second))) {
                EventLog.add(context, "Yakindaki subeler haritadan cekiliyor...")
                cache = poiStore.refresh(here.first, here.second, list)
            }
        }

        val targets = buildTargets(list, cache)
        runCatching { client.removeGeofences(pendingIntent()).await() }

        if (targets.isEmpty()) {
            EventLog.add(context, "Kurulacak yer yok")
            return@runCatching 0
        }

        val fences = targets.map { t ->
            Geofence.Builder()
                .setRequestId(t.fenceId)
                .setCircularRegion(t.lat, t.lng, t.place.fenceMeters)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .setNotificationResponsiveness(0)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        }.toMutableList()

        // Bolge cemberi: buradan cikinca subeleri yenile
        if (hasBrand && cache.updatedAt != 0L) {
            fences += Geofence.Builder()
                .setRequestId(AREA_FENCE_ID)
                .setCircularRegion(cache.centerLat, cache.centerLng, 4000f)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(0)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences)
            .build()

        client.addGeofences(request, pendingIntent()).await()
        EventLog.add(context, "${targets.size} nokta izlemeye alindi")
        Log.i(TAG, "${fences.size} geofence kuruldu")
        targets.size
    }.onFailure {
        EventLog.add(context, "Cember kurulamadi: ${it.message}")
    }

    companion object {
        const val TAG = "GecGec"
    }
}
