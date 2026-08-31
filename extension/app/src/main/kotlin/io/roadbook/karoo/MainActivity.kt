package io.roadbook.karoo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.hammerhead.karooext.KarooSystemService
import io.roadbook.karoo.build.BuildController
import io.roadbook.karoo.build.BuildState
import io.roadbook.karoo.data.Category
import io.roadbook.karoo.data.ConfigStore
import io.roadbook.karoo.data.PlacesClient
import io.roadbook.karoo.data.Poi
import io.roadbook.karoo.data.PoiDatabase
import io.roadbook.karoo.data.PoiQuery
import io.roadbook.karoo.data.Region
import io.roadbook.karoo.data.RegionCatalog
import io.roadbook.karoo.data.RegionCatalogClient
import io.roadbook.karoo.data.RegionManifestEntry
import io.roadbook.karoo.data.RoadbookConfig
import io.roadbook.karoo.data.RoadbookRepository
import io.roadbook.karoo.data.WikipediaClient
import io.roadbook.karoo.ui.FilterScreen
import io.roadbook.karoo.ui.PoiDetailScreen
import io.roadbook.karoo.ui.RegionDownloadState
import io.roadbook.karoo.ui.RegionsScreen
import io.roadbook.karoo.ui.WaybookScreen
import io.roadbook.karoo.ui.field.ACTION_BUILD
import io.roadbook.karoo.ui.field.EXTRA_ACTION
import io.roadbook.karoo.ui.hoursFor
import io.roadbook.karoo.util.withKarooConnection
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In-app screens. No nav framework — a small sealed state the host switches on. */
private sealed interface Screen {
    data object Waybook : Screen
    data object Filter : Screen
    data object Regions : Screen
    data class Detail(val poiId: String) : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var repository: RoadbookRepository
    private lateinit var query: Deferred<PoiQuery>
    private val regionCatalog: List<Region> by lazy { RegionCatalog.load(applicationContext) }

    // Region download state, hoisted so it survives navigation between screens.
    private val downloadState =
        androidx.compose.runtime.mutableStateOf<RegionDownloadState>(RegionDownloadState.Idle)
    private val regionManifest =
        androidx.compose.runtime.mutableStateOf<Map<String, RegionManifestEntry>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(applicationContext)
        repository = RoadbookRepository.get(applicationContext)
        // Seeding the ~310k-row Germany DB on first launch is too slow for the main thread;
        // build the query off-thread and await it where a build actually needs it.
        query = lifecycleScope.async(Dispatchers.IO) { PoiQuery(PoiDatabase.get(applicationContext)) }

        // Launched from the "Tap to build" data field: kick off a build immediately and
        // land on the Filter screen so the rider sees status (and can tweak categories).
        val buildOnLaunch =
            intent?.getStringExtra(EXTRA_ACTION) == ACTION_BUILD
        if (buildOnLaunch) runBuild()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RoadbookApp(initialScreen = if (buildOnLaunch) Screen.Filter else Screen.Waybook)
                }
            }
        }
    }

    @Composable
    private fun RoadbookApp(initialScreen: Screen = Screen.Waybook) {
        val config by configStore.config.collectAsStateWithLifecycle(initialValue = RoadbookConfig())
        val buildState by repository.buildState.collectAsStateWithLifecycle()
        val pois by repository.pois.collectAsStateWithLifecycle()
        val routeLength by repository.routeLengthMeters.collectAsStateWithLifecycle()

        var screen: Screen by remember { mutableStateOf(initialScreen) }
        // Hoisted here so the list scroll position is preserved across navigation to
        // the detail/filter screens and back.
        val waybookListState = rememberLazyListState()

        when (val s = screen) {
            is Screen.Waybook -> WaybookScreen(
                pois = pois,
                routeLengthMeters = routeLength,
                buildState = buildState,
                onBuild = ::runBuild,
                onClear = {
                    repository.clear()
                    repository.setBuildState(BuildState.Idle)
                },
                onOpenFilter = { screen = Screen.Filter },
                onOpenPoi = { screen = Screen.Detail(it.id) },
                // OSM hours, or a Google result already fetched this session → badge in list.
                hoursOf = { poi -> hoursFor(poi, repository.cachedHours(poi.id)?.hours) },
                listState = waybookListState,
            )

            is Screen.Filter -> FilterScreen(
                config = config,
                buildState = buildState,
                hasPins = pois.isNotEmpty(),
                onDetourChange = { m -> lifecycleScope.launch { configStore.setDetour(m) } },
                onCategoryToggle = { c, on ->
                    lifecycleScope.launch { configStore.setCategoryEnabled(c, on) }
                },
                onBuild = ::runBuild,
                onClear = {
                    repository.clear()
                    repository.setBuildState(BuildState.Idle)
                },
                onOpenRegions = { screen = Screen.Regions },
                onBack = { screen = Screen.Waybook },
            )

            is Screen.Regions -> {
                val installed by configStore.installedRegions
                    .collectAsStateWithLifecycle(initialValue = emptySet())
                // Fetch the manifest once on entry (unless a download is mid-flight).
                LaunchedEffect(Unit) {
                    if (regionManifest.value.isEmpty() &&
                        downloadState.value !is RegionDownloadState.Downloading
                    ) {
                        loadManifest()
                    }
                }
                RegionsScreen(
                    regions = regionCatalog,
                    manifest = regionManifest.value,
                    // A fresh install has the Germany seed but an empty set (no download
                    // written); show the seed as installed without a first-run write.
                    installedRegions = installed.ifEmpty { setOf(Region.SEED_REGION_ID) },
                    state = downloadState.value,
                    onDownload = ::downloadRegion,
                    onRemove = ::removeRegion,
                    onBack = { screen = Screen.Waybook },
                )
            }

            is Screen.Detail -> {
                val poi = pois.firstOrNull { it.id == s.poiId }
                if (poi == null) {
                    // POI vanished (e.g. cleared while open) — bounce back.
                    screen = Screen.Waybook
                } else {
                    // Offer a Google hours lookup only when OSM has none, the category
                    // is one where hours matter, and an API key is configured.
                    val googleEligible = poi.tags["opening_hours"] == null &&
                        Category.ofType(poi.type) in GOOGLE_HOURS_CATEGORIES &&
                        BuildConfig.PLACES_API_KEY.isNotEmpty()
                    PoiDetailScreen(
                        poi = poi,
                        hasRoute = routeLength > 0,
                        cachedDescription = repository.cachedDescription(poi.id),
                        loadDescription = { fetchDescription(poi) },
                        cachedGoogleHours = repository.cachedHours(poi.id),
                        loadGoogleHours = if (googleEligible) {
                            { fetchGoogleHours(poi) }
                        } else {
                            null
                        },
                        onBack = { screen = Screen.Waybook },
                    )
                }
            }
        }
    }

    /** Build from the app by spinning up a short-lived Karoo connection. */
    private fun runBuild() {
        repository.setBuildState(BuildState.Building("Connecting…"))
        val system = KarooSystemService(applicationContext)
        system.connect { connected ->
            if (!connected) {
                repository.setBuildState(BuildState.Error("Karoo not connected"))
                return@connect
            }
            lifecycleScope.launch {
                BuildController(system, configStore, repository, query.await()).runBuild()
                system.disconnect()
            }
        }
    }

    /**
     * Fetch a place description via the Karoo HTTP bridge (works over the paired
     * phone, not just WiFi), caching the result so re-opening is instant and it
     * survives offline. Uses a short-lived connection like [runBuild].
     */
    private suspend fun fetchDescription(poi: Poi): String? {
        repository.cachedDescription(poi.id)?.let { return it }
        return withKarooConnection(applicationContext) { system ->
            WikipediaClient(system).summaryFor(poi.tags["wikipedia"])
                ?.also { repository.cacheDescription(poi.id, it) }
        }
    }

    /**
     * Live opening-hours lookup via Google Places (only when OSM has none). The Place
     * ID is cached (allowed by Maps ToS); the hours are returned for display but never
     * persisted. Short-lived connection like [fetchDescription].
     */
    private suspend fun fetchGoogleHours(poi: Poi): PlacesClient.Result? {
        // Serve a still-fresh cached fetch (performance cache) without a network call.
        repository.cachedHours(poi.id)?.let { return it }
        return withKarooConnection(applicationContext) { system ->
            PlacesClient(system)
                .hoursFor(
                    name = poi.name,
                    lat = poi.lat,
                    lng = poi.lng,
                    knownPlaceId = repository.cachedPlaceId(poi.id),
                )
                ?.also {
                    repository.cachePlaceId(poi.id, it.placeId) // Place ID: persisted
                    repository.cacheHours(poi.id, it)           // hours: memory, short TTL
                }
        }
    }

    /** Fetch the region manifest for download sizes; updates [regionManifest]/[downloadState]. */
    private fun loadManifest() {
        downloadState.value = RegionDownloadState.LoadingManifest
        lifecycleScope.launch {
            val manifest = withKarooConnection(applicationContext) { system ->
                RegionCatalogClient(system).fetchManifest()
            }
            if (manifest == null) {
                downloadState.value = RegionDownloadState.ManifestFailed
            } else {
                regionManifest.value = manifest.regions.associateBy { it.id }
                downloadState.value = RegionDownloadState.Idle
                // Stash the full manifest (baseUrl) for the download step.
                lastManifest = manifest
            }
        }
    }

    private var lastManifest: io.roadbook.karoo.data.RegionManifest? = null

    /** Download + install [region], driving [downloadState] through progress → done/failed. */
    private fun downloadRegion(region: Region) {
        val manifest = lastManifest ?: return
        val entry = manifest.regions.firstOrNull { it.id == region.id } ?: return
        downloadState.value = RegionDownloadState.Downloading(region.id, 0f)
        lifecycleScope.launch {
            val result = withKarooConnection(applicationContext) { system ->
                RegionCatalogClient(system).downloadAndInstall(
                    context = applicationContext,
                    manifest = manifest,
                    entry = entry,
                    scratchDir = cacheDir,
                    onProgress = { p ->
                        // Full progress covers the download; install is the short tail.
                        downloadState.value = if (p.done >= p.total && p.total > 0) {
                            RegionDownloadState.Installing(region.id)
                        } else {
                            RegionDownloadState.Downloading(region.id, p.fraction)
                        }
                    },
                )
            }
            downloadState.value = when (result) {
                is RegionCatalogClient.Result.Installed -> {
                    // Additive: record the region alongside any already installed. If this
                    // is the first explicit download on a seed-only install, also record the
                    // seed so removing this region doesn't hide the still-present Germany.
                    if (configStore.installedRegions.first().isEmpty()) {
                        configStore.addInstalledRegion(Region.SEED_REGION_ID)
                    }
                    configStore.addInstalledRegion(region.id)
                    RegionDownloadState.Done(region.id, result.poiCount)
                }
                is RegionCatalogClient.Result.SchemaMismatch ->
                    RegionDownloadState.Failed(region.id, "Update the app to download regions")
                is RegionCatalogClient.Result.Failed ->
                    RegionDownloadState.Failed(region.id, result.reason)
                null -> RegionDownloadState.Failed(region.id, "No connection")
            }
        }
    }

    /** Remove an installed region's POIs, then drop it from the installed set. */
    private fun removeRegion(region: Region) {
        downloadState.value = RegionDownloadState.Installing(region.id)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                PoiDatabase.removeRegion(applicationContext, region.id)
            }
            configStore.removeInstalledRegion(region.id)
            downloadState.value = RegionDownloadState.Idle
        }
    }

    private companion object {
        // Categories where opening hours matter enough to spend a Google lookup.
        val GOOGLE_HOURS_CATEGORIES = setOf(
            Category.SUPERMARKETS, Category.CAFE_BAR, Category.RESTAURANTS, Category.FUEL,
            Category.ICE_CREAM, Category.HOTELS,
        )
    }
}
