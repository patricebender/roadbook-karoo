package io.roadbook.karoo.extension

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import io.roadbook.karoo.data.Category
import io.roadbook.karoo.data.ConfigStore
import io.roadbook.karoo.data.RoadbookRepository
import io.roadbook.karoo.data.formatDetour
import io.roadbook.karoo.data.formatKm
import io.roadbook.karoo.data.elideName
import io.roadbook.karoo.data.upcomingByCategory
import io.roadbook.karoo.ui.field.BuildPromptField
import io.roadbook.karoo.ui.field.CategoryRow
import io.roadbook.karoo.ui.field.PoiCell
import io.roadbook.karoo.ui.field.FieldMessage
import io.roadbook.karoo.ui.field.LargeUpcomingField
import io.roadbook.karoo.ui.field.OffRouteMessage
import io.roadbook.karoo.ui.field.SmallUpcomingField
import io.roadbook.karoo.util.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * "Upcoming POIs" data field. Renders, per enabled category, the next POIs ahead on
 * the route and how far. Size-adaptive:
 *  - large (≥2 grid rows): one row per category, three POIs each;
 *  - small: a single category, condensed. Auto-rotates through categories so the
 *    rider sees each without interaction (tap-to-cycle isn't reliably available on a
 *    Karoo graphical field — see plan; auto-rotate is the robust fallback).
 *
 * Everything is on-device: POIs and route length come from [RoadbookRepository],
 * live route progress from the `DISTANCE_TO_DESTINATION` stream.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class UpcomingPoisDataType(
    private val karooSystem: KarooSystemService,
    private val repository: RoadbookRepository,
    private val configStore: ConfigStore,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("startView upcoming-pois grid=${config.gridSize} size=${config.viewSize}")
        // Use pixel height, not grid rows: a short-but-wide slot (e.g. 1/8 ≈ 130px tall)
        // can't stack all categories legibly, so fall back to the single-category layout.
        // Full-screen is ~720px; ~40px/row means ≥300px comfortably fits the multi-row view.
        val large = config.viewSize.second >= LARGE_MIN_HEIGHT_PX

        // Custom graphical field — don't let Karoo overlay a numeric header.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val scope = CoroutineScope(Dispatchers.IO)

        // Small field rotates through categories; large shows them all at once.
        val rotation = MutableStateFlow(0L)
        if (!large) {
            scope.launch {
                var tick = 0L
                while (isActive) {
                    kotlinx.coroutines.delay(ROTATE_MS)
                    rotation.value = ++tick
                }
            }
        }

        val progressFlow = karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)
            .map { it as? StreamState.Streaming }

        // MainActivity, launched when the rider taps the "Tap to build" prompt.
        val mainActivity = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS)

        val job = scope.launch {
            combine(
                repository.pois,
                repository.routeLengthMeters,
                configStore.config.map { it.enabledCategories },
                progressFlow,
                rotation,
            ) { pois, routeLen, enabled, stream, tick ->
                Frame(pois, routeLen, enabled, stream, tick)
            }.collect { f ->
                val remoteViews = glance.compose(context, DpSize.Unspecified) {
                    render(f, large, mainActivity)
                }.remoteViews
                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable {
            Timber.d("stopView upcoming-pois")
            job.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private data class Frame(
        val pois: List<io.roadbook.karoo.data.Poi>,
        val routeLenMeters: Double,
        val enabled: Set<Category>,
        val stream: StreamState.Streaming?,
        val rotationTick: Long,
    )

    @androidx.compose.runtime.Composable
    private fun render(f: Frame, large: Boolean, mainActivity: ComponentName) {
        if (f.enabled.isEmpty()) return FieldMessage("Enable a category")
        // "Tap to build" is ONLY for the genuine no-roadbook case. Once POIs exist we
        // always show them — even without a live route stream (we just measure from km 0).
        if (f.pois.isEmpty()) return BuildPromptField(mainActivity)

        // Live route progress, when available: route length − distance-to-destination.
        // Missing stream / no route length ⇒ progress 0 (show POIs from the start) rather
        // than falling back to the build prompt, which hid an already-built roadbook.
        val values = f.stream?.dataPoint?.values
        val toDest = values?.get(DataType.Field.DISTANCE_TO_DESTINATION)
        val progress = if (f.routeLenMeters > 0.0 && toDest != null) {
            (f.routeLenMeters - toDest).coerceIn(0.0, f.routeLenMeters)
        } else {
            0.0
        }

        // Flag a genuine mid-ride deviation (ON_ROUTE=false after real progress); before
        // the start ON_ROUTE=false just means "not joined yet" and we show POIs anyway.
        val onRoute = values?.get(DataType.Field.ON_ROUTE)?.let { it >= 0.5 } ?: true
        if (!onRoute && progress > START_GRACE_METERS) return OffRouteMessage()

        val upcoming = upcomingByCategory(f.pois, f.enabled, progress)

        if (large) {
            // Only show categories that actually have something ahead — an enabled but
            // empty category would just be a label with blank cells (clutter). Keep the
            // enabled order.
            val rows = f.enabled
                .filter { upcoming[it]?.isNotEmpty() == true }
                .map { cat -> rowFor(cat, upcoming.getValue(cat), large = true) }
            if (rows.isEmpty()) return FieldMessage("No POIs ahead")
            LargeUpcomingField(rows, mainActivity)
        } else {
            // One category at a time, rotating. Prefer categories that have POIs ahead so
            // the small field isn't stuck on an empty one; fall back to all if none do.
            val cats = f.enabled.filter { upcoming[it]?.isNotEmpty() == true }
                .ifEmpty { f.enabled.toList() }
            val cat = cats[(f.rotationTick % cats.size).toInt()]
            SmallUpcomingField(rowFor(cat, upcoming[cat].orEmpty(), large = false), mainActivity)
        }
    }

    /**
     * Build a [CategoryRow] of up to three [PoiCell]s. All keep the `km` unit; the UI
     * renders the unit in a smaller font on follow-ups so it fits the ~255dp width.
     */
    private fun rowFor(
        category: Category,
        pois: List<io.roadbook.karoo.data.UpcomingPoi>,
        large: Boolean,
    ): CategoryRow {
        val cells = pois.mapIndexed { i, p ->
            PoiCell(
                distance = formatKm(p.aheadMeters),
                // Chevrons mark "further along the route": nearest bare, 2nd ›, 3rd ».
                arrow = when (i) {
                    0 -> ""
                    1 -> "›"
                    else -> "»"
                },
                detour = formatDetour(p.detourMeters).removePrefix("·").takeIf { it.isNotEmpty() },
                detourMeters = p.detourMeters,
            )
        }
        val nameMax = if (large) NAME_MAX_LARGE else NAME_MAX_SMALL
        return CategoryRow(
            category = category,
            name = elideName(pois.firstOrNull()?.name, nameMax),
            cells = cells,
        )
    }

    companion object {
        const val TYPE_ID = "upcoming-pois"
        private const val MAIN_ACTIVITY_CLASS = "io.roadbook.karoo.MainActivity"
        // Min field height (px) for the multi-category layout; below this we show one
        // category. Full-screen ≈720px, a 1/8 slot ≈130px. 300px ≈ room for ~4 rows.
        private const val LARGE_MIN_HEIGHT_PX = 300
        private const val ROTATE_MS = 5_000L
        private const val NAME_MAX_LARGE = 18
        private const val NAME_MAX_SMALL = 12
        // Below this progress, treat ON_ROUTE=false as "not started yet" (rider is
        // approaching the route start) rather than a mid-ride deviation.
        private const val START_GRACE_METERS = 500.0
    }
}
