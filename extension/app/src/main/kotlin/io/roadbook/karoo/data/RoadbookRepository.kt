package io.roadbook.karoo.data

import android.content.Context
import io.roadbook.karoo.build.BuildState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
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

    fun setBuildState(state: BuildState) {
        _buildState.value = state
    }

    init {
        // Load any previously built POIs so they're on the map offline immediately.
        runCatching {
            if (cacheFile.exists()) {
                _pois.value = json.decodeFromString(poiListSerializer, cacheFile.readText())
                Timber.d("loaded ${_pois.value.size} cached POIs")
            }
        }.onFailure { Timber.w(it, "failed to load POI cache") }
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
