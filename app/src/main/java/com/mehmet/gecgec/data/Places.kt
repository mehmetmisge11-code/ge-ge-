package com.mehmet.gecgec.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "📍",
    val targetPackage: String = "",
    val targetLabel: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** Dis cember: buraya girince telefon hassas GPS takibine gecer. 100'un altina inme. */
    val fenceMeters: Float = 150f,
    /** Asil tetikleme mesafesi. Ses + titresim + uygulama burada calisir. */
    val triggerMeters: Float = 20f,
    val cooldownMinutes: Int = 60,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val enabled: Boolean = true
) {
    val isReady: Boolean get() = targetPackage.isNotEmpty() && (lat != 0.0 || lng != 0.0)
}

val DEFAULT_PLACES = listOf(
    Place(id = "gym", name = "Spor salonu", emoji = "🏋"),
    Place(id = "starbucks", name = "Starbucks", emoji = "☕"),
    Place(id = "sok", name = "Şok", emoji = "🛒"),
    Place(id = "ev", name = "Ev", emoji = "🏠")
)

private val Context.dataStore by preferencesDataStore("gecgec")
private val PLACES_KEY = stringPreferencesKey("places_json")
private val SEEDED_V2 = booleanPreferencesKey("seeded_v2")
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

    /** Eski surumden gelenlere "Ev" gibi yeni hazir yerleri bir kez ekler. */
    suspend fun ensureSeeded() {
        val done = context.dataStore.data.first()[SEEDED_V2] ?: false
        if (done) return
        val current = load()
        val missing = DEFAULT_PLACES.filter { d -> current.none { it.id == d.id } }
        if (missing.isNotEmpty()) save(current + missing)
        context.dataStore.edit { it[SEEDED_V2] = true }
    }

    suspend fun update(id: String, transform: (Place) -> Place) {
        save(load().map { if (it.id == id) transform(it) else it })
    }

    suspend fun add(place: Place) = save(load() + place)

    suspend fun delete(id: String) = save(load().filterNot { it.id == id })
}

/**
 * Ne oldu, ne zaman oldu. Uygulamanin ana ekraninda gorunur.
 * SharedPreferences kullaniyoruz cunku servis/receiver icinden aninda yazilabiliyor.
 */
object EventLog {
    private const val PREFS = "gecgec_log"
    private const val KEY = "lines"
    private const val MAX = 40

    fun add(context: Context, text: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date())
        val old = p.getString(KEY, "").orEmpty().split("\n").filter { it.isNotBlank() }
        val lines = (listOf("$stamp  $text") + old).take(MAX)
        p.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun read(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
            .split("\n").filter { it.isNotBlank() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
