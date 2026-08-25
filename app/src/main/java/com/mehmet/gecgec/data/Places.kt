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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
enum class PlaceKind { FIXED, BRAND }

@Serializable
data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "📍",
    val kind: PlaceKind = PlaceKind.FIXED,
    val searchText: String = "",
    val targetPackage: String = "",
    val targetLabel: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val fenceMeters: Float = 150f,
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

@Serializable
data class Poi(val lat: Double, val lng: Double, val label: String = "")

@Serializable
data class PoiCache(
    val centerLat: Double = 0.0,
    val centerLng: Double = 0.0,
    val updatedAt: Long = 0L,
    val byPlace: Map<String, List<Poi>> = emptyMap()
)

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

fun buildTargets(places: List<Place>, cache: PoiCache): List<Target> {
    val out = mutableListOf<Target>()
    for (p in places.filter { it.enabled && it.isReady }) {
        when (p.kind) {
            PlaceKind.FIXED -> out += Target(p.id, p, p.lat, p.lng, p.name)
            PlaceKind.BRAND ->
                cache.byPlace[p.id].orEmpty().take(MAX_PER_BRAND).forEachIndexed { i, poi ->
                    out += Target("${p.id}#$i", p, poi.lat, poi.lng, poi.label.ifBlank { p.name })
                }
        }
    }
    return out.take(MAX_FENCES)
}

fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val o = FloatArray(1)
    Location.distanceBetween(aLat, aLng, bLat, bLng, o)
    return o[0].toDouble()
}

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
        context.dataStore.edit { it[POI_KEY] = json.encodeToString(PoiCache.serializer(), cache) }
    }

    fun isStale(cache: PoiCache, lat: Double, lng: Double): Boolean {
        if (cache.updatedAt == 0L) return true
        if (System.currentTimeMillis() - cache.updatedAt > 6 * 60 * 60 * 1000L) return true
        return distanceMeters(lat, lng, cache.centerLat, cache.centerLng) > 2500.0
    }

    suspend fun refresh(lat: Double, lng: Double, places: List<Place>): PoiCache {
        val brands = places.filter { it.enabled && it.kind == PlaceKind.BRAND && it.isReady }
        if (brands.isEmpty()) return load()

        val old = load()
        val map = old.byPlace.toMutableMap()

        for (b in brands) {
            val found = MapSearch.nearby(context, b.searchText, lat, lng)
            if (found.isNotEmpty()) {
                map[b.id] = found.sortedBy { distanceMeters(lat, lng, it.lat, it.lng) }
                EventLog.add(context, "${b.name}: ${found.size} sube")
            } else {
                EventLog.add(context, "${b.name}: sube bulunamadi (eski liste korundu)")
            }
        }

        val cache = PoiCache(lat, lng, System.currentTimeMillis(), map)
        save(cache)
        return cache
    }
}

object MapSearch {

    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
    private const val RADIUS_M = 6000
    private const val UA = "GecGec/1.0 (kisisel kullanim)"

    private fun looseRegex(text: String): String {
        val sb = StringBuilder()
        for (ch in text.trim()) {
            when {
                ch == ' ' -> sb.append(" ")
                !ch.isLetterOrDigit() -> {}
                else -> {
                    val l = ch.lowercaseChar()
                    val cls = when (l) {
                        's', 'ş' -> "sSşŞ"
                        'c', 'ç' -> "cCçÇ"
                        'g', 'ğ' -> "gGğĞ"
                        'i', 'ı' -> "iIıİ"
                        'o', 'ö' -> "oOöÖ"
                        'u', 'ü' -> "uUüÜ"
                        else -> "$l${l.uppercaseChar()}"
                    }
                    sb.append("[").append(cls).append("]")
                }
            }
        }
        return sb.toString()
    }

    suspend fun nearby(context: Context, text: String, lat: Double, lng: Double): List<Poi> =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext emptyList()

            val rx = looseRegex(text)
            val query = """
                [out:json][timeout:25];
                (
                  nwr["name"~"$rx"](around:$RADIUS_M,$lat,$lng);
                  nwr["brand"~"$rx"](around:$RADIUS_M,$lat,$lng);
                );
                out center 80;
            """.trimIndent()

            for (url in OVERPASS) {
                val r = post(url, "data=" + URLEncoder.encode(query, "UTF-8"))
                if (r.first == 200) {
                    val list = parseOverpass(r.second)
                    if (list.isNotEmpty()) return@withContext list
                } else {
                    EventLog.add(context, "Harita sunucusu yanit vermedi (${r.first})")
                }
            }

            val d = 0.06
            val vb = "${lng - d},${lat + d},${lng + d},${lat - d}"
            val q = URLEncoder.encode(text.trim(), "UTF-8")
            val r = get("$NOMINATIM?format=jsonv2&q=$q&limit=50&bounded=1&viewbox=$vb")
            if (r.first == 200) {
                val list = parseNominatim(r.second)
                if (list.isNotEmpty()) {
                    EventLog.add(context, "Yedek harita kullanildi")
                    return@withContext list
                }
            } else {
                EventLog.add(context, "Yedek harita da yanit vermedi (${r.first})")
            }

            emptyList()
        }

    suspend fun geocode(text: String): List<Poi> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()
        val q = URLEncoder.encode(text.trim(), "UTF-8")
        val r = get("$NOMINATIM?format=jsonv2&q=$q&limit=6&accept-language=tr")
        if (r.first == 200) parseNominatim(r.second) else emptyList()
    }

    private fun get(url: String): Pair<Int, String> = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "tr,en")
        }
        val code = c.responseCode
        val body = (if (code == 200) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        code to body
    }.getOrElse { -1 to (it.message ?: "baglanti hatasi") }

    private fun post(url: String, form: String): Pair<Int, String> = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", UA)
        }
        c.outputStream.use { it.write(form.toByteArray()) }
        val code = c.responseCode
        val body = (if (code == 200) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        code to body
    }.getOrElse { -1 to (it.message ?: "baglanti hatasi") }

    private fun parseOverpass(body: String): List<Poi> = runCatching {
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<Poi>()
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            val la: Double
            val lo: Double
            if (e.has("lat") && e.has("lon")) {
                la = e.optDouble("lat"); lo = e.optDouble("lon")
            } else {
                val c = e.optJSONObject("center") ?: continue
                la = c.optDouble("lat"); lo = c.optDouble("lon")
            }
            if (la.isNaN() || lo.isNaN()) continue
            out += Poi(la, lo, e.optJSONObject("tags")?.optString("name
