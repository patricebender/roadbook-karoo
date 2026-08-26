package io.roadbook.karoo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import io.roadbook.karoo.BuildConfig
import io.roadbook.karoo.util.decodePolyline
import timber.log.Timber

/**
 * Milestone 1: prove the extension registers, can read the loaded route, and can
 * draw a pin on the native map. When a route is being navigated we drop a single
 * marker at the route's midpoint. No backend, no config yet.
 */
class RoadbookExtension : KarooExtension("roadbook", BuildConfig.VERSION_NAME) {

    private var karooSystem: KarooSystemService? = null

    override fun startMap(emitter: Emitter<MapEffect>) {
        Timber.d("startMap: connecting to Karoo system")
        val system = KarooSystemService(applicationContext)
        karooSystem = system

        var consumerId: String? = null
        system.connect { connected ->
            Timber.d("Karoo system connected=$connected")
            if (!connected) return@connect
            consumerId = system.addConsumer<OnNavigationState> { event ->
                onNavigationState(event, emitter)
            }
        }

        emitter.setCancellable {
            Timber.d("startMap: cancelled, tearing down")
            consumerId?.let { system.removeConsumer(it) }
            system.disconnect()
            karooSystem = null
        }
    }

    private fun onNavigationState(event: OnNavigationState, emitter: Emitter<MapEffect>) {
        when (val state = event.state) {
            is OnNavigationState.NavigationState.NavigatingRoute -> {
                val points = decodePolyline(state.routePolyline)
                if (points.isEmpty()) {
                    Timber.w("route polyline decoded to 0 points")
                    return
                }
                val mid = points[points.size / 2]
                Timber.d("route loaded: ${points.size} pts, midpoint=$mid")
                emitter.onNext(
                    ShowSymbols(
                        listOf(
                            Symbol.POI(
                                id = "roadbook-test-pin",
                                lat = mid.first,
                                lng = mid.second,
                                type = Symbol.POI.Types.GENERIC,
                                name = "Roadbook test pin",
                            ),
                        ),
                    ),
                )
            }
            else -> {
                Timber.d("no active route; hiding test pin")
                emitter.onNext(HideSymbols(listOf("roadbook-test-pin")))
            }
        }
    }
}
