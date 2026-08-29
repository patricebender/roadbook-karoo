package io.roadbook.karoo.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.roadbook.karoo.R
import io.roadbook.karoo.build.BuildState
import io.roadbook.karoo.data.OpeningHours
import io.roadbook.karoo.data.Poi
import java.util.Calendar

// Neutral tint for the "hours exist but are seasonal/complex" chip.
private val SeasonalGrey = Color(0xFF757575)

/**
 * The Waybook ROUTE view: a header with build/clear/filter shortcuts and a live build
 * status line, the route distance strip, and a scrollable list of the POIs found along
 * the route. Tapping a row opens the place detail.
 */
@Composable
fun WaybookScreen(
    pois: List<Poi>,
    routeLengthMeters: Double,
    buildState: BuildState,
    onBuild: () -> Unit,
    onClear: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenPoi: (Poi) -> Unit,
    // Resolves a POI's hours (OSM, or a Google result already fetched this session) so
    // the list badge shows for both sources once known.
    hoursOf: (Poi) -> OpeningHours.Hours?,
    // Hoisted so the scroll position survives opening/closing the detail view.
    listState: LazyListState,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            buildState = buildState,
            hasPins = pois.isNotEmpty(),
            // When the body shows the big animated logo (first build, no pins yet),
            // keep the header status line quiet so there's only one progress cue.
            showStatusLine = pois.isNotEmpty() || buildState !is BuildState.Building,
            onBuild = onBuild,
            onClear = onClear,
            onOpenFilter = onOpenFilter,
        )
        HorizontalDivider()

        if (pois.isEmpty()) {
            // While the first build runs with nothing to show yet, the animated mark
            // takes over the body (the header spinner is suppressed to avoid two).
            if (buildState is BuildState.Building) {
                BuildingState(buildState.phase)
            } else {
                EmptyState(onOpenFilter)
            }
            return@Column
        }

        RouteStrip(pois = pois, routeLengthMeters = routeLengthMeters)
        HorizontalDivider()

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(pois, key = { it.id }) { poi ->
                PoiRow(
                    poi = poi,
                    hours = hoursOf(poi),
                    hasRoute = routeLengthMeters > 0,
                    onClick = { onOpenPoi(poi) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}

@Composable
private fun Header(
    buildState: BuildState,
    hasPins: Boolean,
    showStatusLine: Boolean,
    onBuild: () -> Unit,
    onClear: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    val building = buildState is BuildState.Building
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Logo mark only — no wordmark. The status line carries the context.
        // Image (not Icon) so the mark keeps its own route/pin gradients instead
        // of being flattened to a single tint.
        Image(
            painter = painterResource(R.drawable.ic_roadbook_mark),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (showStatusLine) BuildStatusLine(buildState)
        }
        // Build/rebuild: primary tint when there's work, muted after a build. While
        // building the icon just goes disabled — the single spinner lives in the
        // status line, so we never show two spinners at once.
        IconButton(onClick = onBuild, enabled = !building) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Build",
                tint = when {
                    building -> MaterialTheme.colorScheme.onSurfaceVariant
                    buildState is BuildState.Success -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
        if (hasPins) {
            IconButton(onClick = onClear, enabled = !building) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear")
            }
        }
        IconButton(onClick = onOpenFilter) {
            Icon(Icons.Filled.Tune, contentDescription = "Filter")
        }
    }
}

/**
 * The header's primary line, only used for transient build feedback: the current build
 * phase (with a spinner) and errors. The steady-state place count lives in the timeline
 * strip below, so idle/success leave this line blank — the logo and action icons carry
 * the header on their own.
 */
@Composable
private fun BuildStatusLine(state: BuildState) {
    when (state) {
        is BuildState.Building -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(
                state.phase,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is BuildState.Error -> Text(
            state.message,
            style = MaterialTheme.typography.titleMedium,
            color = ClosedRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is BuildState.Success, is BuildState.Idle -> Unit
    }
}

@Composable
private fun PoiRow(
    poi: Poi,
    hours: OpeningHours.Hours?,
    hasRoute: Boolean,
    onClick: () -> Unit,
) {
    val style = styleForType(poi.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(style.color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                style.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                poi.name ?: "Unnamed",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Line 2: type + status badge. Line 3 (when closed): "opens Mon 08:00".
            TypeAndStatusLine(hours = hours, typeLabel = style.label)
            // Detour (only meaningful along a route).
            if (hasRoute && poi.detourMeters > 0) {
                Text(
                    "detour ${formatDistance(poi.detourMeters)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Type label + a status chip on one line; when closed, the "opens …" text drops to its
 * own line so it stays readable on the narrow display instead of truncating.
 */
@Composable
private fun TypeAndStatusLine(hours: OpeningHours.Hours?, typeLabel: String) {
    val status = remember(hours) { hours?.status() }
    // Hours exist but couldn't be structured (seasonal/complex) → flag without claiming.
    val seasonal = hours?.rawFallback != null

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                typeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(8.dp))
            when {
                seasonal -> StatusChip("Seasonal", SeasonalGrey)
                status?.state == OpeningHours.OpenState.OPEN -> StatusChip("Open", OpenGreen)
                status?.state == OpeningHours.OpenState.CLOSED -> StatusChip("Closed", ClosedRed)
                // UNKNOWN or no hours: type only, no status claim.
                else -> Unit
            }
        }
        if (status?.state == OpeningHours.OpenState.CLOSED) {
            status.opensAtLabel(todayIndex())?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A small filled status pill (Open / Closed / Seasonal). */
@Composable
private fun StatusChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * Body shown during the first build (no places yet): the animated roadbook mark traces
 * its route and lights up waypoints one after another, with the current build phase below.
 * This replaces a bare header spinner so the wait itself reinforces the brand.
 */
@Composable
private fun BuildingState(phase: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RoadbookLoadingLogo(size = 112.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            phase,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(onOpenFilter: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_roadbook_mark),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No places yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Load a route on the Karoo, then tap the build icon above.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.clickable(onClick = onOpenFilter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Tune, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Open filter", style = MaterialTheme.typography.titleSmall)
        }
    }
}
