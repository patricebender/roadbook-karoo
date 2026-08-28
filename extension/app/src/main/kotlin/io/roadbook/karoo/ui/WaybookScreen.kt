package io.roadbook.karoo.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.roadbook.karoo.data.OpeningHours
import io.roadbook.karoo.data.Poi
import java.util.Calendar

/**
 * The Waybook ROUTE view: a header with a filter shortcut, the route distance strip,
 * and a scrollable list of the POIs found along the route. Tapping a row opens the
 * place detail. Mirrors the mockup.
 */
@Composable
fun WaybookScreen(
    pois: List<Poi>,
    routeLengthMeters: Double,
    onOpenFilter: () -> Unit,
    onOpenPoi: (Poi) -> Unit,
    // Resolves a POI's hours (OSM, or a Google result already fetched this session) so
    // the list badge shows for both sources once known.
    hoursOf: (Poi) -> OpeningHours.Hours?,
    // Hoisted so the scroll position survives opening/closing the detail view.
    listState: LazyListState,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header(count = pois.size, onOpenFilter = onOpenFilter)
        HorizontalDivider()

        if (pois.isEmpty()) {
            EmptyState(onOpenFilter)
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
private fun Header(count: Int, onOpenFilter: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Waybook", style = MaterialTheme.typography.headlineSmall)
            if (count > 0) {
                Text(
                    "$count places",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenFilter) {
            Icon(Icons.Filled.Tune, contentDescription = "Filter")
        }
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
            // Line 2: "Café · Open" / "Gas station · Closed · opens 06:00" / "Gas station".
            TypeAndStatusLine(hours = hours, typeLabel = style.label)
            // Line 3: detour (only meaningful along a route).
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

/** "Café · Open", "Gas station · Closed · opens 06:00", or just "Gas station". */
@Composable
private fun TypeAndStatusLine(hours: OpeningHours.Hours?, typeLabel: String) {
    val status = remember(hours) { hours?.status() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            typeLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (status?.state) {
            OpeningHours.OpenState.OPEN -> StatusText(" · Open", OpenGreen)
            OpeningHours.OpenState.CLOSED -> {
                StatusText(" · Closed", ClosedRed)
                status.opensAtLabel(todayIndex())?.let { label ->
                    Text(
                        " · $label",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // UNKNOWN or no hours tag: type only, no status claim.
            else -> Unit
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1)
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
        Text(
            "No places yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Load a route on the Karoo, then build your roadbook from the filter screen.",
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
            Text("Open filter & build", style = MaterialTheme.typography.titleSmall)
        }
    }
}
