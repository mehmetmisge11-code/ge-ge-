package com.mehmet.gecgec.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class Place(
    val id: String,
    val name: String,
    val emoji: String,
    val targetPackage: String = "",
    val targetLabel: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val radiusMeters: Float = 130f,
    val dwellSeconds: Int = 40,
    val cooldownMinutes: Int = 90,
    val enabled: Boolean = true
) {
    val isReady: Boolean get() = targetPackage.isNotEmpty() && (lat != 0.0 || lng != 0.0)
}

val DEFAULT_PLACES = listOf(
    Place(id = "gym", name = "Spor salonu", emoji = "🏋"),
    Place(id = "starbucks", name = "Starbucks", emoji = "☕"),
    Place(id = "sok", name = "Şok", emoji = "🛒")
)

private val Context.dataStore by preferencesDataStore("gecgec")
private val PLACES_KEY = stringPreferencesKey("places_json")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val placesSerializer = ListSerializer(Place.serializer())

class PlaceStore(private val context: Context) {

    val placesFlow: Flow<List<Place>> = context.dataStore.data.map { prefs ->
        prefs[PLACES_KEY]
            ?.let { runCatching { json.decodeFromString(placesSerializer, it) }.getOrNull() }
            ?: DEFAULT_PLACES
    }

    suspend fun load(): List<Place> = placesFlow.first()

    suspend fun save(places: List<Place>) {
        context.dataStore.edit { it[PLACES_KEY] = json.encodeToString(placesSerializer, places) }
    }

    suspend fun update(id: String, transform: (Place) -> Place) {
        save(load().map { if (it.id == id) transform(it) else it })
    }
}
