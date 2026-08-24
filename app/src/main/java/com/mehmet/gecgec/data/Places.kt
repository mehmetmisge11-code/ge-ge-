package com.mehmet.gecgec.data

import android.content.Context
import android.location.Location
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ==================== MODEL ====================

@Serializable
enum class PlaceKind {
    /** Tek bir nokta - "buradayken bas" ile kaydedilir. */
    FIXED,

    /** Marka - yakindaki TUM subeler haritadan bulunur. */
    BRAND
}

@Serializable
data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "📍",
    val kind: PlaceKind = PlaceKind.FIXED,
    /** BRAND icin haritada aranacak metin, or. "Starbucks" */
    val searchText: String = "",
    val targetPackage: String = "",
    val targetLabel: String = "",
    /** FIXED icin kayitli konum */
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** Dis cember: buraya girince telefon hassas GPS takibine gecer. */
    val fenceMeters: Float = 150f,
    /** Asil tetikleme mesafesi. */
    val triggerMeters: Float = 20f,
    val cooldownMinutes: Int = 60,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val enabled: Boolean = true
) {
    val isReady: Boolean
        get() = targetPackage.isNotEmpty() && when (kind) {
            PlaceKind.FIXED -> lat != 0.0 || lng != 0.0
            PlaceKind.BRAND -> searchText.isNotBlank()
        }
}

val DEFAULT_PLACES = listOf(
    Place(id = "gym", name = "Spor salonu", emoji = "🏋"),
    Place(
        id = "starbucks", name = "Starbucks", emoji = "☕",
        kind = PlaceKind.BRAND, searchText = "Starbucks"
    ),
    Place(
        id = "sok", name = "Şok", emoji = "🛒",
        kind = PlaceKind.BRAND, searchText = "Şok"
    ),
    Place(id = "ev", name = "Ev", emoji = "🏠")
)

/** Haritadan bulunmus tek bir sube. */
@Serializable
data class Poi(val lat: Double, val lng: Double, val label: String = "")

@Serializable
data class PoiCache(
    val centerLat: Double = 0.0,
    val centerLng: Double = 0.0,
    val updatedAt: Long = 0L,
    val byPlace: Map<String, List<Poi>> = emptyMap()
)

/** Kurulacak tek bir cember: hangi yer, hangi nokta. */
data class Target(
    val fenceId: String,
    val place: Place,
    val lat: Double,
    val lng: Double,
    val label: String
)

const val AREA_FENCE_ID = "__area__"
private const val MAX_FENCES = 90
private const val MAX_PER_BRAND = 35

/** Yerleri + bulunmus subeleri tek bir cember listesine cevirir. */
fun buildTargets(places: List<Place>, cache: PoiCache): List<Target> {
    val out = mutableListOf<Target>()
    for (p in places.filter { it.enabled && it.isReady }) {
        when (p.kind) {
            PlaceKind.FIXED -> out += Target(p.id, p, p.lat, p.lng, p.name)
            PlaceKind.BRAND -> {
                cache.byPlace[p.id].orEmpty().take(MAX_PER_BRAND).forEachIndexed { i, poi ->
                    out += Target("${p.id}#$i", p, poi.lat, poi.lng, poi.label.ifBlank { p.name })
                }
            }
        }
    }
    return out.take(MAX_FENCES)
}

fun placeIdOf(fenceId: String): String = fenceId.substringBefore('#')

// ==================== KAYIT ====================

private val Context.dataStore by preferencesDataStore("gecgec")
private val PLACES_KEY = stringPreferencesKey("places_json")
private val POI_KEY = stringPreferencesKey("poi_json")
private val SEEDED_V3 = booleanPreferencesKey("seeded_v3")
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

    /**
     * Eski surumden gelenler icin bir kerelik duzeltme:
     * Ev'i ekler, Starbucks ve Sok'u marka moduna cevirir (kayitli uygulamayi korur).
     */
    suspend fun ensureSeeded() {
        if (context.dataStore.data.first()[SEEDED_V3] == true) return
        var list = load()

        DEFAULT_PLACES.filter { d -> list.none { it.id == d.id } }
            .let { missing -> if (missing.isNotEmpty()) list = list + missing }

        list = list.map { p ->
            when (p.id) {
                "starbucks" -> p.copy(kind = PlaceKind.BRAND, searchText = "Starbucks")
                "sok" -> p.copy(kind = PlaceKind.BRAND, searchText = "Şok")
                else -> p
            }
        }

        save(list)
        context.dataStore.edit { it[SEEDED_V3] = true }
    }

    suspend fun update(id: String, transform: (Place) -> Place) {
        save(load().map { if (it.id == id) transform(it) else it })
    }

    suspend fun add(place: Place) = save(load() + place)

    suspend fun delete(id: String) = save(load().filterNot { it.id == id })
}

class PoiStore(private val context: Context) {

    suspend fun load(): PoiCache = context.dataStore.data.first()[POI_KEY]
        ?.let { runCatching { json.decodeFromString(PoiCache.serializer(), it) }.getOrNull() }
        ?: PoiCache()

    suspend fun save(cache: PoiCache) {
        context.dataStore.edit {
            it[POI_KEY] = json.encodeToString(PoiCache.serializer(), cache)
        }
    }

    /** Cache eskiyse veya cok uzaklasildiysa yenilemek gerekir. */
    fun isStale(cache: PoiCache, lat: Double, lng: Double): Boolean {
        if (cache.updatedAt == 0L) return true
        if (System.currentTimeMillis() - cache.updatedAt > 12 * 60 * 60 * 1000L) return true
        val out = FloatArray(1)
        Location.distanceBetween(lat, lng, cache.centerLat, cache.centerLng, out)
        return out[0] > 3000f
    }

    /** Yakindaki subeleri haritadan ceker ve kaydeder. */
    suspend fun refresh(lat: Double, lng: Double, places: List<Place>): PoiCache {
        val brands = places.filter { it.enabled && it.kind == PlaceKind.BRAND && it.isReady }
        val map = mutableMapOf<String, List<Poi>>()

        for (b in brands) {
            val found = Overpass.search(b.searchText, lat, lng)
            val sorted = found.sortedBy { poi ->
                val o = FloatArray(1)
                Location.distanceBetween(lat, lng, poi.lat, poi.lng, o)
                o[0]
            }
            map[b.id] = sorted
            EventLog.add(context, "${b.name}: ${sorted.size} sube bulundu")
        }

        val cache = PoiCache(lat, lng, System.currentTimeMillis(), map)
        save(cache)
        return cache
    }
}

// ==================== HARITA SORGUSU (OpenStreetMap / Overpass) ====================

object Overpass {

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val RADIUS_M = 6000

    /** Yalnizca harf/rakam/bosluk birakir - sorguyu bozacak karakterleri atar. */
    private fun clean(s: String) = s.filter { it.isLetterOrDigit() || it == ' ' }.trim()

    suspend fun search(text: String, lat: Double, lng: Double): List<Poi> =
        withContext(Dispatchers.IO) {
            val q = clean(text)
            if (q.isBlank()) return@withContext emptyList()

            val body = """
                [out:json][timeout:25];
                (
                  nwr["name"~"$q",i](around:$RADIUS_M,$lat,$lng);
                  nwr["brand"~"$q",i](around:$RADIUS_M,$lat,$lng);
                );
                out center 80;
            """.trimIndent()

            runCatching {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", "GecGec/1.0")
                }
                conn.outputStream.use {
                    it.write(("data=" + URLEncoder.encode(body, "UTF-8")).toByteArray())
                }
                val text2 = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                parse(text2)
            }.getOrElse { emptyList() }
        }

    private fun parse(body: String): List<Poi> {
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<Poi>()
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            val lat = if (e.has("lat")) e.optDouble("lat")
            else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.optDouble("lon")
            else e.optJSONObject("center")?.optDouble("lon") ?: continue
            if (lat.isNaN() || lon.isNaN()) continue
            val label = e.optJSONObject("tags")?.optString("name").orEmpty()
            out += Poi(lat, lon, label)
        }
        // Ayni noktayi iki kez saymayalim
        return out.distinctBy { "%.5f,%.5f".format(it.lat, it.lng) }
    }
}

// ==================== OLAY KAYDI ====================

object EventLog {
    private const val PREFS = "gecgec_log"
    private const val KEY = "lines"
    private const val MAX = 50

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
