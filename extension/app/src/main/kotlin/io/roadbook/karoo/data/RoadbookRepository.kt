package io.roadbook.karoo.data

import android.content.Context
import io.roadbook.karoo.build.BuildState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import timber.log.Timber

/**
 * App-scoped holder for the currently built POIs. Exposes a [StateFlow] the map
 * layer observes, and persists to a JSON file so POIs survive a process restart
 * and are available offline mid-ride.
 */
class RoadbookRepository private constructor(private val cacheFile: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val poiListSerializer = ListSerializer(Poi.serializer())

    private val _pois = MutableStateFlow<List<Poi>>(emptyList())
    val pois: StateFlow<List<Poi>> = _pois.asStateFlow()

    private val _buildState = MutableStateFlow<BuildState>(BuildState.Idle)
    val buildState: StateFlow<BuildState> = _buildState.asStateFlow()

    /**
     * Total length of the route the current POIs were built against, in meters.
     * 0 when the last build was /nearby (no route) — the strip hides itself then.
     * Positions POI dots on the route strip; not persisted (rebuilt each session).
     */
    private val _routeLengthMeters = MutableStateFlow(0.0)
    val routeLengthMeters: StateFlow<Double> = _routeLengthMeters.asStateFlow()

    /** In-memory cache of fetched place descriptions, keyed by POI id. */
    private val descriptions = mutableMapOf<String, String>()

    /**
     * POI id → resolved Google Place ID. Persisted: Maps ToS permits caching Place IDs
     * indefinitely, so we skip the Text Search on repeat hours lookups. (Hours
     * themselves are never cached — those are fetched live each time.)
     */
    private val placeIdCache = mutableMapOf<String, String>()

    fun setBuildState(state: BuildState) {
        _buildState.value = state
    }

    fun setRouteLength(meters: Double) {
        _routeLengthMeters.value = meters
    }

    fun cachedDescription(poiId: String): String? = descriptions[poiId]

    fun cacheDescription(poiId: String, text: String) {
        descriptions[poiId] = text
    }

    /**
     * In-memory cache of fetched Google opening hours, keyed by POI id, with a short
     * TTL. Maps ToS permits temporary caching *solely for performance* (not a permanent
     * store) — a bike ride is well within that. NOT persisted; gone when the process
     * ends, and re-fetched once older than [HOURS_TTL_MS].
     */
    private data class CachedHours(val result: PlacesClient.Result, val atMs: Long)
    private val hoursCache = mutableMapOf<String, CachedHours>()

    fun cachedHours(poiId: String): PlacesClient.Result? =
        hoursCache[poiId]?.takeIf { System.currentTimeMillis() - it.atMs < HOURS_TTL_MS }?.result

    fun cacheHours(poiId: String, result: PlacesClient.Result) {
        hoursCache[poiId] = CachedHours(result, System.currentTimeMillis())
    }

    fun cachedPlaceId(poiId: String): String? = placeIdCache[poiId]

    fun cachePlaceId(poiId: String, placeId: String) {
        placeIdCache[poiId] = placeId
        persistPlaceIds()
    }

    // Sibling file for the persisted POI id → Google Place ID map.
    private val placeIdFile = File(cacheFile.parentFile, "roadbook_place_ids.json")
    private val placeIdSerializer = MapSerializer(String.serializer(), String.serializer())

    init {
        // Load any previously built POIs so they're on the map offline immediately.
        runCatching {
            if (cacheFile.exists()) {
                _pois.value = json.decodeFromString(poiListSerializer, cacheFile.readText())
                Timber.d("loaded ${_pois.value.size} cached POIs")
            }
        }.onFailure { Timber.w(it, "failed to load POI cache") }

        // Load persisted Place IDs (allowed to keep indefinitely per Maps ToS).
        runCatching {
            if (placeIdFile.exists()) {
                placeIdCache.putAll(
                    json.decodeFromString(placeIdSerializer, placeIdFile.readText()),
                )
            }
        }.onFailure { Timber.w(it, "failed to load place-id cache") }
    }

    private fun persistPlaceIds() {
        runCatching { placeIdFile.writeText(json.encodeToString(placeIdSerializer, placeIdCache)) }
            .onFailure { Timber.w(it, "failed to persist place-id cache") }
    }

    /** Replace the current POIs and persist them for offline use. */
    fun setPois(pois: List<Poi>) {
        _pois.value = pois
        persist()
    }

    /** Clear POIs at the start of a new build. */
    fun clear() {
        _pois.value = emptyList()
        persist()
    }

    /** Append a page of POIs (dedup by id) and persist. */
    fun appendPois(page: List<Poi>) {
        val existing = _pois.value.associateBy { it.id }.toMutableMap()
        for (p in page) existing[p.id] = p
        _pois.value = existing.values.toList()
        persist()
    }

    private fun persist() {
        runCatching { cacheFile.writeText(json.encodeToString(poiListSerializer, _pois.value)) }
            .onFailure { Timber.w(it, "failed to persist POI cache") }
    }

    companion object {
        // Performance-cache window for fetched Google hours (ToS: short-term only).
        private const val HOURS_TTL_MS = 12 * 60 * 60 * 1000L

        @Volatile
        private var instance: RoadbookRepository? = null

        fun get(context: Context): RoadbookRepository =
            instance ?: synchronized(this) {
                instance ?: RoadbookRepository(
                    File(context.applicationContext.filesDir, "roadbook_pois.json"),
                ).also { instance = it }
            }
    }
}
