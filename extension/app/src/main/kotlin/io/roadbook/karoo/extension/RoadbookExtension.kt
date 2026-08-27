package io.roadbook.karoo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import io.roadbook.karoo.BuildConfig
import io.roadbook.karoo.build.BuildController
import io.roadbook.karoo.data.ConfigStore
import io.roadbook.karoo.data.Poi
import io.roadbook.karoo.data.RoadbookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The Roadbook map-layer extension.
 *
 * - `onBonusAction("build")` triggers a build for the current route/location.
 * - `startMap` observes the shared [RoadbookRepository] and draws the built POIs
 *   as map pins, redrawing whenever a build updates them.
 */
class RoadbookExtension : KarooExtension("roadbook", BuildConfig.VERSION_NAME) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: RoadbookRepository
    private lateinit var configStore: ConfigStore

    override fun onCreate() {
        super.onCreate()
        repository = RoadbookRepository.get(applicationContext)
        configStore = ConfigStore(applicationContext)
    }

    override fun onBonusAction(actionId: String) {
        if (actionId != ACTION_BUILD) return
        Timber.d("onBonusAction: build")
        scope.launch {
            val system = KarooSystemService(applicationContext)
            system.connect { connected ->
                if (!connected) return@connect
                scope.launch {
                    val controller = BuildController(system, configStore, repository)
                    controller.runBuild()
                    system.disconnect()
                }
            }
        }
    }

    override fun startMap(emitter: Emitter<MapEffect>) {
        Timber.d("startMap: observing roadbook POIs")
        var shownIds = emptyList<String>()

        val job = repository.pois
            .onEach { pois ->
                // Remove any pins no longer present, then show the current set.
                val newIds = pois.map { it.id }
                val removed = shownIds - newIds.toSet()
                if (removed.isNotEmpty()) emitter.onNext(HideSymbols(removed))
                if (pois.isNotEmpty()) emitter.onNext(ShowSymbols(pois.map { it.toSymbol() }))
                shownIds = newIds
                Timber.d("map: drew ${pois.size} POIs")
            }
            .launchIn(scope)

        emitter.setCancellable {
            Timber.d("startMap: cancelled")
            job.cancel()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun Poi.toSymbol(): Symbol.POI = Symbol.POI(
        id = id,
        lat = lat,
        lng = lng,
        type = type,
        name = name,
        distancesAlongRoute = distancesAlongRoute,
    )

    private companion object {
        const val ACTION_BUILD = "build"
    }
}
