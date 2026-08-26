package com.mehmet.gecgec.data

import android.content.Context
import android.location.Location
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

// ==================== MODEL ====================

@Serializable
enum class PlaceKind {
    /** Tek bir nokta. */
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
    /**
     * Dis cember: buraya girince telefon hassas GPS takibine gecer.
     * Buyuk olmasi sart - Android cemberi hemen haber vermiyor, arabadayken
     * kucuk cember icinden haber gelmeden cikiyorsun.
     */
    val fenceMeters: Float = 500f,
    /** Asil tetikleme mesafesi. */
    val triggerMeters: Float = 40f,
    val cooldownMinutes: Int = 30,
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
    /** Haritadan bulunanlar. Her yenilemede bastan yazilir. */
    val byPlace: Map<String, List<Poi>> = emptyMap(),
    /**
     * Elle eklenen veya kendi kendine ogrenilen subeler.
     * Yenilemede ASLA silinmez - haritanin bilmedigi subeler burada durur.
     */
    val manual: Map<String, List<Poi>> = emptyMap(),
    /** Kullanicinin "bu yanlis" dedigi noktalar. Yeniden eklenmezler. */
    val blocked: Map<String, List<Poi>> = emptyMap()
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

/**
 * Cember kimligi koordinattan uretilir - liste yenilenince sira degisse bile
 * ayni sube ayni kimligi alir. Bekleme suresi (cooldown) bu sayede sasmaz.
 */
private fun fenceIdOf(placeId: String, poi: Poi): String =
    "$placeId#${(poi.lat * 10000).toInt()}_${(poi.lng * 10000).toInt()}"

/**
 * Marka icin izlenecek subeler: elle eklenenler + haritadan gelenler,
 * son tarama merkezine gore YAKINDAN UZAGA siralanmis.
 *
 * Siralama sart: yuzlerce sube kayitli olabilir ama Android sadece
 * sinirli sayida cember kabul ediyor. Siralamasak sehrin obur ucundaki
 * subeler yuzunden yanindaki sube izlenmeden kalirdi.
 */
fun brandPois(placeId: String, cache: PoiCache): List<Poi> {
    val manual = cache.manual[placeId].orEmpty()
    val blocked = cache.blocked[placeId].orEmpty()
    fun isBlocked(p: Poi) = blocked.any { distanceMeters(it.lat, it.lng, p.lat, p.lng) < 60.0 }

    val found = cache.byPlace[placeId].orEmpty().filter { f ->
        !isBlocked(f) && manual.none { distanceMeters(it.lat, it.lng, f.lat, f.lng) < 120.0 }
    }
    val all = manual.filterNot { isBlocked(it) } + found
    if (cache.centerLat == 0.0 && cache.centerLng == 0.0) return all
    return all.sortedBy { distanceMeters(cache.centerLat, cache.centerLng, it.lat, it.lng) }
}

/**
 * @param max toplam kac nokta
 * @param perBrand marka basina kac sube
 *
 * Android en fazla 100 cember kabul ediyor, o yuzden CEMBER kurarken
 * varsayilan (90/35) kullanilir. Ama TrackerService konumu kendisi olctugu
 * icin onun boyle bir siniri yok - o cok daha buyuk sayilarla cagirir ve
 * sehirdeki subelerin HEPSINI izler.
 */
fun buildTargets(
    places: List<Place>,
    cache: PoiCache,
    max: Int = MAX_FENCES,
    perBrand: Int = MAX_PER_BRAND
): List<Target> {
    val out = mutableListOf<Target>()
    for (p in places.filter { it.enabled && it.isReady }) {
        when (p.kind) {
            PlaceKind.FIXED -> out += Target(p.id, p, p.lat, p.lng, p.name)
            PlaceKind.BRAND ->
                brandPois(p.id, cache).take(perBrand).forEach { poi ->
                    out += Target(
                        fenceIdOf(p.id, poi), p, poi.lat, poi.lng,
                        poi.label.ifBlank { p.name }
                    )
                }
        }
    }
    return out.distinctBy { it.fenceId }.take(max)
}

fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val o = FloatArray(1)
    Location.distanceBetween(aLat, aLng, bLat, bLng, o)
    return o[0].toDouble()
}

// ==================== KAYIT ====================

private val Context.dataStore by preferencesDataStore("gecgec")
private val PLACES_KEY = stringPreferencesKey("places_json")
private val POI_KEY = stringPreferencesKey("poi_json")
private val SEEDED_V3 = booleanPreferencesKey("seeded_v3")
private val HERE_KEY = stringPreferencesKey("here_api_key")
private val TRACK_KEY = intPreferencesKey("track_mode")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val placesSerializer = ListSerializer(Place.serializer())

/** Takip sikligi. Pil ile gecikme arasindaki denge. */
enum class TrackMode {
    /** Sadece Android'in cemberi. Pil yemez ama arabayla gecerken kacirir. */
    OFF,

    /** Surekli takip, seyrek olcum. Orta yol. */
    SAVER,

    /** Surekli takip, tam hiz. Gecikme yok, gunde ~%5-10 pil. */
    FULL
}

class SettingsStore(private val context: Context) {
    val modeFlow: Flow<TrackMode> = context.dataStore.data.map { prefs ->
        when (prefs[TRACK_KEY]) {
            0 -> TrackMode.OFF
            1 -> TrackMode.SAVER
            else -> TrackMode.FULL
        }
    }

    suspend fun load(): TrackMode = modeFlow.first()

    suspend fun save(mode: TrackMode) {
        context.dataStore.edit {
            it[TRACK_KEY] = when (mode) {
                TrackMode.OFF -> 0
                TrackMode.SAVER -> 1
                TrackMode.FULL -> 2
            }
        }
    }
}

/**
 * HERE anahtari. Samsung'un ve araba navigasyonlarinin kullandigi harita verisi.
 * Bos birakilirsa uygulama OpenStreetMap ile calisir - sadece daha az sube bulur.
 */
class KeyStore(private val context: Context) {
    val keyFlow: Flow<String> = context.dataStore.data.map { it[HERE_KEY].orEmpty() }
    suspend fun load(): String = keyFlow.first()
    suspend fun save(key: String) {
        context.dataStore.edit { it[HERE_KEY] = key.trim() }
    }
}

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

    /**
     * Liste ne zaman bayat sayilir.
     * DIKKAT: bu esikler kucuk olacak. Buyuk olursa sehrin obur ucundaki
     * eski liste uzerinde kalir ve "en yakin 16 km" gibi hayalet kayitlar cikar.
     */
    fun isStale(cache: PoiCache, lat: Double, lng: Double): Boolean {
        if (cache.updatedAt == 0L) return true
        if (System.currentTimeMillis() - cache.updatedAt > 2 * 60 * 60 * 1000L) return true
        return distanceMeters(lat, lng, cache.centerLat, cache.centerLng) > 800.0
    }

    suspend fun refresh(lat: Double, lng: Double, places: List<Place>): PoiCache {
        val brands = places.filter { it.enabled && it.kind == PlaceKind.BRAND && it.isReady }
        if (brands.isEmpty()) return load()

        val old = load()
        val map = old.byPlace.toMutableMap()

        for (b in brands) {
            val found = MapSearch.nearby(context, b.searchText, lat, lng)
            // Bulunamadiysa eski listeyi silme - internetsiz kalinca calismaya devam etsin
            if (found.isNotEmpty()) {
                // Yuzlerce sube saklanabilir; cember kurulurken en yakin
                // MAX_PER_BRAND tanesi kullanilir.
                map[b.id] = found
                    .sortedBy { distanceMeters(lat, lng, it.lat, it.lng) }
                    .take(400)
                EventLog.add(context, "${b.name}: ${found.size} şube")
            } else {
                EventLog.add(context, "${b.name}: şube bulunamadı (eski liste korundu)")
            }
        }

        val cache = old.copy(
            centerLat = lat, centerLng = lng,
            updatedAt = System.currentTimeMillis(),
            byPlace = map
        )
        save(cache)
        return cache
    }

    /**
     * Elle / kendi kendine sube ekler.
     * @return true ise gercekten yeni bir sube eklendi.
     */
    suspend fun addManual(
        placeId: String, lat: Double, lng: Double, label: String
    ): Boolean = withContext(Dispatchers.IO) {
        val c = load()
        val near = { p: Poi -> distanceMeters(p.lat, p.lng, lat, lng) < 150.0 }
        // Zaten biliyorsak (haritadan ya da elden) tekrar ekleme
        if (c.manual[placeId].orEmpty().any(near)) return@withContext false
        if (c.byPlace[placeId].orEmpty().any(near)) return@withContext false

        val m = c.manual.toMutableMap()
        m[placeId] = c.manual[placeId].orEmpty() + Poi(lat, lng, label)
        // "Yanlis" diye isaretlenmisse listeden cikar - kullanici fikrini degistirmis
        val bl = c.blocked.toMutableMap()
        bl[placeId] = c.blocked[placeId].orEmpty().filterNot(near)
        save(c.copy(manual = m, blocked = bl))
        true
    }

    /**
     * "Sehri tara" sonucunu kaydeder. Kalici listeye yazilir - normal
     * yenileme bunlari silmez, sehirden ayrilinca kaybolmazlar.
     * @return kac YENI sube eklendi
     */
    suspend fun addManualBulk(placeId: String, list: List<Poi>): Int =
        withContext(Dispatchers.IO) {
            if (list.isEmpty()) return@withContext 0
            val c = load()
            val blocked = c.blocked[placeId].orEmpty()
            val keep = (c.manual[placeId].orEmpty()).toMutableList()
            val known = (keep + c.byPlace[placeId].orEmpty()).toMutableList()
            var added = 0

            for (p in list) {
                if (blocked.any { distanceMeters(it.lat, it.lng, p.lat, p.lng) < 60.0 }) continue
                if (known.any { distanceMeters(it.lat, it.lng, p.lat, p.lng) < 120.0 }) continue
                keep += p
                known += p
                added++
            }
            if (added == 0) return@withContext 0

            val m = c.manual.toMutableMap()
            m[placeId] = keep
            save(c.copy(manual = m))
            added
        }

    /** "Bu sube yanlis" - hem listeden siler hem bir daha eklenmesini engeller. */
    suspend fun blockPoi(placeId: String, poi: Poi) = withContext(Dispatchers.IO) {
        val c = load()
        val near = { p: Poi -> distanceMeters(p.lat, p.lng, poi.lat, poi.lng) < 60.0 }
        val man = c.manual.toMutableMap()
        man[placeId] = c.manual[placeId].orEmpty().filterNot(near)
        val found = c.byPlace.toMutableMap()
        found[placeId] = c.byPlace[placeId].orEmpty().filterNot(near)
        val bl = c.blocked.toMutableMap()
        bl[placeId] = c.blocked[placeId].orEmpty() + poi
        save(c.copy(manual = man, byPlace = found, blocked = bl))
    }
}

// ==================== HARITA ====================

/**
 * Sube arama. Once Overpass sunuculari denenir (uc yedek), olmazsa Nominatim.
 * Her hata "Olan biten"e yazilir - sessizce bosluga dusmesin.
 */
object MapSearch {

    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
    private const val PHOTON = "https://photon.komoot.io/api/"
    private const val HERE_DISCOVER = "https://discover.search.hereapi.com/v1/discover"

    /**
     * 20 km. Onceden 6 km idi - arabayla giderken 6 km'lik liste
     * daha sen sehrin obur ucuna varmadan tukeniyordu.
     */
    private const val RADIUS_M = 20000
    private const val UA = "GecGec/1.0 (kisisel kullanim)"

    /** Turkce harfleri sadelestirir: "ŞOK Market" -> "sok market" */
    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(
                when (ch) {
                    'ş', 'Ş' -> 's'
                    'ı', 'İ' -> 'i'
                    'ğ', 'Ğ' -> 'g'
                    'ü', 'Ü' -> 'u'
                    'ö', 'Ö' -> 'o'
                    'ç', 'Ç' -> 'c'
                    'â', 'Â' -> 'a'
                    else -> ch.lowercaseChar()
                }
            )
        }
        return sb.toString()
    }

    /**
     * Photon ve Nominatim marka aramasinda cadde/mahalle de dondurebiliyor.
     * Adinda marka gecmeyen sonuclari eliyoruz.
     */
    private fun matchesBrand(label: String, brand: String): Boolean =
        label.isNotBlank() && norm(label).contains(norm(brand.trim()))

    /**
     * Turkce buyuk/kucuk harf sorununu cozer.
     * "Şok" -> "[sSşŞ][oOöÖ][kK]" — hem SOK, hem ŞOK, hem Şok eslesir.
     */
    private fun looseRegex(text: String): String {
        val sb = StringBuilder()
        for (ch in text.trim()) {
            when {
                ch == ' ' -> sb.append(" ")
                !ch.isLetterOrDigit() -> {} // sorguyu bozacak karakterleri at
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

    /**
     * Yakindaki subeleri bulur. Uc Overpass sunucusu + Photon + Nominatim denenir,
     * hepsinin sonucu birlestirilip ayni noktalar teke indirilir. Bir kaynak coker
     * veya bir subeyi atlarsa digeri yakaliyor.
     */
    suspend fun nearby(context: Context, text: String, lat: Double, lng: Double): List<Poi> =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext emptyList()

            val all = mutableListOf<Poi>()
            var sources = 0

            // 0) HERE - Samsung'un ve araba navigasyonlarinin kullandigi veri.
            //    Anahtar varsa EN IYI kaynak bu, digerleri sadece takviye.
            val hereKey = runCatching { KeyStore(context).load() }.getOrDefault("")
            if (hereKey.isNotBlank()) {
                val h = get(
                    "$HERE_DISCOVER?in=circle:$lat,$lng;r=$RADIUS_M" +
                        "&q=${URLEncoder.encode(text.trim(), "UTF-8")}" +
                        "&limit=100&lang=tr&apiKey=$hereKey"
                )
                if (h.first == 200) {
                    val list = parseHere(h.second).filter { matchesBrand(it.label, text) }
                    if (list.isNotEmpty()) { all += list; sources++ }
                    EventLog.add(context, "HERE: ${list.size} şube")
                } else {
                    EventLog.add(
                        context,
                        "HERE yanıt vermedi (${h.first}) — anahtarı kontrol et"
                    )
                }
            }

            // 1) Overpass - en dogru kaynak, marka etiketiyle arar
            val rx = looseRegex(text)
            val query = """
                [out:json][timeout:40];
                (
                  nwr["name"~"$rx"](around:$RADIUS_M,$lat,$lng);
                  nwr["brand"~"$rx"](around:$RADIUS_M,$lat,$lng);
                  nwr["operator"~"$rx"](around:$RADIUS_M,$lat,$lng);
                );
                out center 250;
            """.trimIndent()

            for (url in OVERPASS) {
                val r = post(url, "data=" + URLEncoder.encode(query, "UTF-8"))
                if (r.first == 200) {
                    val list = parseOverpass(r.second)
                    if (list.isNotEmpty()) {
                        all += list
                        sources++
                        break   // bir Overpass yeterli, digerleri yedek
                    }
                } else {
                    EventLog.add(context, "Harita 1 yanıt vermedi (${r.first})")
                }
            }

            // 2) Photon - bagimsiz ikinci kaynak
            val q = URLEncoder.encode(text.trim(), "UTF-8")
            val ph = get("$PHOTON?q=$q&lat=$lat&lon=$lng&limit=80")
            if (ph.first == 200) {
                val list = parsePhoton(ph.second).filter {
                    distanceMeters(lat, lng, it.lat, it.lng) <= RADIUS_M &&
                        matchesBrand(it.label, text)
                }
                if (list.isNotEmpty()) { all += list; sources++ }
            }

            // 3) Nominatim - ucuncu kaynak (kutu ~22 km)
            val d = 0.20
            val vb = "${lng - d},${lat + d},${lng + d},${lat - d}"
            val nm = get("$NOMINATIM?format=jsonv2&q=$q&limit=50&bounded=1&viewbox=$vb")
            if (nm.first == 200) {
                val list = parseNominatim(nm.second).filter {
                    distanceMeters(lat, lng, it.lat, it.lng) <= RADIUS_M &&
                        matchesBrand(it.label, text)
                }
                if (list.isNotEmpty()) { all += list; sources++ }
            }

            if (sources == 0) {
                EventLog.add(context, "Hiçbir harita kaynağına ulaşılamadı")
                return@withContext emptyList()
            }

            val merged = mergeNearby(all)
            EventLog.add(context, "$text: $sources kaynak, ${merged.size} şube")
            merged
        }

    /** Ayni magazayi iki kaynaktan da bulduysak teke indir (35 metreden yakinlar ayni sayilir). */
    private fun mergeNearby(list: List<Poi>): List<Poi> {
        val out = mutableListOf<Poi>()
        for (p in list) {
            val hit = out.firstOrNull { distanceMeters(it.lat, it.lng, p.lat, p.lng) < 35.0 }
            if (hit == null) out += p
            else if (hit.label.isBlank() && p.label.isNotBlank()) {
                out[out.indexOf(hit)] = hit.copy(label = p.label)
            }
        }
        return out
    }

    /**
     * SEHRI TARA
     *
     * Tek bir sorgu en fazla 100 sonuc donuyor ve 25 km'lik daire bir sehri
     * kapsamiyor. Bu yuzden sehir 5x5'lik kareler halinde taranir; her kareye
     * ayri sorgu gider, sonuclar birlestirilip tekrarlar atilir.
     *
     * ~55x55 km alan, 25 sorgu. HERE'in gunluk 1000 hakkinin yaninda hicbir sey.
     */
    suspend fun sweepCity(
        context: Context,
        text: String,
        lat: Double,
        lng: Double,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Poi> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()

        val key = runCatching { KeyStore(context).load() }.getOrDefault("")

        val all: List<Poi> = if (key.isBlank()) {
            // ---- ANAHTARSIZ YOL ----
            // DIKKAT: burada 25 ayri sorgu ATMA. OpenStreetMap sunuculari
            // her sorguda 30-45 saniye tutuyor; 25 tanesi 15 dakika ediyor
            // ve uygulama donmus gorunuyor. Overpass zaten tek sorguda
            // 50 km'yi tarayabiliyor - bir sorgu yeter.
            onProgress(1, 2)
            val rx = looseRegex(text)
            val q = """
                [out:json][timeout:90];
                (
                  nwr["name"~"$rx"](around:45000,$lat,$lng);
                  nwr["brand"~"$rx"](around:45000,$lat,$lng);
                  nwr["operator"~"$rx"](around:45000,$lat,$lng);
                );
                out center 1200;
            """.trimIndent()

            var found = emptyList<Poi>()
            for (url in OVERPASS) {
                val r = post(url, "data=" + URLEncoder.encode(q, "UTF-8"), 95_000)
                if (r.first == 200) {
                    found = parseOverpass(r.second)
                    if (found.isNotEmpty()) break
                } else {
                    EventLog.add(context, "Harita yanıt vermedi (${r.first}), yedek deneniyor")
                }
            }
            onProgress(2, 2)
            found
        } else {
            // ---- HERE YOLU ----
            // Tek sorgu en fazla 100 sonuc donuyor, o yuzden sehir
            // 25 kareye bolunuyor. Kareler AYNI ANDA cekiliyor (besli gruplar),
            // tek tek beklemiyoruz.
            val tiles = buildList {
                for (i in -2..2) for (j in -2..2) add((lat + i * 0.10) to (lng + j * 0.125))
            }
            val out = mutableListOf<Poi>()
            var done = 0
            for (group in tiles.chunked(5)) {
                val parts = group.map { (cy, cx) ->
                    async {
                        val r = get(
                            "$HERE_DISCOVER?in=circle:$cy,$cx;r=9000" +
                                "&q=${URLEncoder.encode(text.trim(), "UTF-8")}" +
                                "&limit=100&lang=tr&apiKey=$key"
                        )
                        if (r.first == 200) parseHere(r.second) else emptyList()
                    }
                }
                parts.forEach { out += it.await() }
                done += group.size
                onProgress(done, tiles.size)
            }
            out
        }

        val merged = mergeNearby(all.filter { matchesBrand(it.label, text) })
        EventLog.add(context, "$text: şehir tarandı — ${merged.size} şube")
        merged
    }

    /**
     * Bulundugun mahallenin adi. Android'in kendi Geocoder'i yerine
     * agdan soruyoruz - bu dosyada zaten calisan HTTP yolunu kullaniyor.
     */
    fun reverse(lat: Double, lng: Double): String {
        val r = get(
            "https://nominatim.openstreetmap.org/reverse" +
                "?format=jsonv2&zoom=16&accept-language=tr&lat=$lat&lon=$lng"
        )
        if (r.first != 200) return ""
        return runCatching {
            val a = JSONObject(r.second).optJSONObject("address") ?: return ""
            val mahalle = listOf("neighbourhood", "suburb", "quarter", "village")
                .map { a.optString(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val ilce = listOf("town", "city_district", "county", "city")
                .map { a.optString(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            listOf(mahalle, ilce).filter { it.isNotBlank() }.distinct().joinToString(", ")
        }.getOrDefault("")
    }

    suspend fun geocode(text: String): List<Poi> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()
        val q = URLEncoder.encode(text.trim(), "UTF-8")
        val r = get("$NOMINATIM?format=jsonv2&q=$q&limit=6&accept-language=tr")
        if (r.first == 200) parseNominatim(r.second) else emptyList()
    }

    // ---- HTTP ----

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

    private fun post(url: String, form: String, readMs: Int = 30000): Pair<Int, String> = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = readMs
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

    // ---- Cozumleme ----

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
            out += Poi(la, lo, e.optJSONObject("tags")?.optString("name").orEmpty())
        }
        out.distinctBy { "%.5f,%.5f".format(it.lat, it.lng) }
    }.getOrElse { emptyList() }

    private fun parseHere(body: String): List<Poi> = runCatching {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Poi>()
        for (i in 0 until items.length()) {
            val it0 = items.optJSONObject(i) ?: continue
            val p = it0.optJSONObject("position") ?: continue
            val la = p.optDouble("lat")
            val lo = p.optDouble("lng")
            if (la.isNaN() || lo.isNaN()) continue
            out += Poi(la, lo, it0.optString("title"))
        }
        out.distinctBy { "%.5f,%.5f".format(it.lat, it.lng) }
    }.getOrElse { emptyList() }

    private fun parsePhoton(body: String): List<Poi> = runCatching {
        val feats = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<Poi>()
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lo = c.optDouble(0)
            val la = c.optDouble(1)
            if (la.isNaN() || lo.isNaN()) continue
            out += Poi(la, lo, f.optJSONObject("properties")?.optString("name").orEmpty())
        }
        out
    }.getOrElse { emptyList() }

    private fun parseNominatim(body: String): List<Poi> = runCatching {
        val arr = JSONArray(body)
        val out = mutableListOf<Poi>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val la = e.optString("lat").toDoubleOrNull() ?: continue
            val lo = e.optString("lon").toDoubleOrNull() ?: continue
            val label = e.optString("name").ifBlank {
                e.optString("display_name").take(60)
            }
            out += Poi(la, lo, label)
        }
        out.distinctBy { "%.5f,%.5f".format(it.lat, it.lng) }
    }.getOrElse { emptyList() }
}

// ==================== MAHALLE ====================

/**
 * Bulundugun mahalleyi bulur - "Liman Mah., Konyaaltı" gibi.
 *
 * Ne ise yarar: mahalle degistiginde sube listesi ANINDA yenilenir.
 * Sadece "1 km yurudun" beklemeye gerek kalmaz; semt degistirdigin an
 * yeni semtin subeleri gelir.
 *
 * Agdan sorar, o yuzden ARKA PLANDAN cagrilmali. Yakin noktalar icin
 * cevap saklanir - her olcumde tekrar sormaz.
 */
object Area {
    private var cachedKey = ""
    private var cachedName = ""

    fun name(lat: Double, lng: Double): String {
        // ~110 metrelik cozunurluk - ayni sokakta tekrar sorma
        val key = ((lat * 1000).toInt()).toString() + "," + ((lng * 1000).toInt()).toString()
        if (key == cachedKey) return cachedName

        val found = MapSearch.reverse(lat, lng)
        if (found.isNotEmpty()) {
            cachedKey = key
            cachedName = found
        }
        return found
    }
}

// ==================== OLAY KAYDI ====================

object EventLog {
    private const val PREFS = "gecgec_log"
    private const val KEY = "lines"
    private const val MAX = 60

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
