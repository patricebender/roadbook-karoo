package io.roadbook.karoo.build

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.SystemNotification
import io.roadbook.karoo.data.Category
import io.roadbook.karoo.data.ConfigStore
import io.roadbook.karoo.data.PoiQuery
import io.roadbook.karoo.data.RoadbookRepository
import io.roadbook.karoo.util.LatLng
import io.roadbook.karoo.util.cumulativeDistances
import io.roadbook.karoo.util.decodeLatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Orchestrates a roadbook build, shared by both triggers (in-app button and the
 * in-ride BonusAction). Reads config, resolves the route (or location), and
 * queries the on-device POI database — no backend, works fully offline.
 */
class BuildController(
    private val system: KarooSystemService,
    private val configStore: ConfigStore,
    private val repository: RoadbookRepository,
    private val query: PoiQuery,
) {
    /** Run a build. Serialized so overlapping triggers can't race. */
    suspend fun runBuild(): BuildState = mutex.withLock {
        val config = configStore.config.first()
        if (config.enabledCategories.isEmpty()) {
            return fail("Enable at least one category")
        }

        repository.clear()
        publish(BuildState.Building())

        return try {
            // Resolve the route (nav state may not emit if unchanged; bound the wait).
            val nav = withTimeoutOrNull(NAV_READ_TIMEOUT_MS) {
                awaitOnce<OnNavigationState>()
            }?.state

            val pois = when (nav) {
                is OnNavigationState.NavigationState.NavigatingRoute -> {
                    val route = decodeLatLng(nav.routePolyline)
                    Timber.d("build along route: ${route.size} pts, detour=${config.detourMeters}")
                    // Cache the route length so the Waybook strip can place POI dots.
                    repository.setRouteLength(cumulativeDistances(route).lastOrNull() ?: 0.0)
                    withContext(Dispatchers.IO) {
                        query.queryCorridor(route, config.detourMeters, config.enabledCategories)
                    }
                }
                else -> {
                    val loc = withTimeoutOrNull(NAV_READ_TIMEOUT_MS) {
                        awaitOnce<OnLocationChanged>()
                    } ?: return fail("No route or location available")
                    Timber.d("build nearby: ${loc.lat},${loc.lng}")
                    repository.setRouteLength(0.0) // nearby: no route → strip hidden
                    withContext(Dispatchers.IO) {
                        query.queryNearby(
                            LatLng(loc.lat, loc.lng), config.detourMeters, config.enabledCategories,
                        )
                    }
                }
            }

            repository.setPois(pois)
            if (pois.isEmpty()) {
                // Likely outside an installed region — guide the user rather than
                // presenting an empty success.
                fail("No POIs here — download this region?")
            } else {
                val byCategory = pois
                    .mapNotNull { Category.ofType(it.type) }
                    .groupingBy { it }
                    .eachCount()
                succeed(pois.size, byCategory)
            }
        } catch (e: Exception) {
            Timber.e(e, "build failed")
            fail(e.message ?: "Build failed")
        }
    }

    /** Consume a single emission of a Karoo event, then unsubscribe. */
    private suspend inline fun <reified T : io.hammerhead.karooext.models.KarooEvent> awaitOnce(): T? =
        suspendCancellableCoroutine { cont ->
            var consumerId: String? = null
            consumerId = system.addConsumer<T> { event ->
                consumerId?.let { system.removeConsumer(it) }
                if (cont.isActive) cont.resume(event)
            }
            cont.invokeOnCancellation { consumerId?.let { system.removeConsumer(it) } }
        }

    private fun succeed(count: Int, byCategory: Map<Category, Int>): BuildState {
        val state = BuildState.Success(count, byCategory, System.currentTimeMillis())
        publish(state)
        notify("Roadbook: $count POIs found")
        return state
    }

    private fun fail(message: String): BuildState {
        val state = BuildState.Error(message)
        publish(state)
        notify("Roadbook: $message")
        return state
    }

    private fun publish(state: BuildState) = repository.setBuildState(state)

    private fun notify(message: String) {
        system.dispatch(
            SystemNotification(id = UUID.randomUUID().toString(), message = message),
        )
    }

    private companion object {
        const val NAV_READ_TIMEOUT_MS = 5_000L
        // One build at a time across the whole process (app + BonusAction).
        val mutex = Mutex()
    }
}
