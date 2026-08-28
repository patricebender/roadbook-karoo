package io.roadbook.karoo.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.roadbook.karoo.data.OpeningHours
import io.roadbook.karoo.data.PlacesClient
import io.roadbook.karoo.data.Poi
import kotlinx.coroutines.launch

/**
 * Place detail: hours + "open now" from OSM `opening_hours`, contact info, distance
 * along the route, and an on-demand Wikipedia description. The description is loaded
 * via [loadDescription] (routed through the Karoo HTTP bridge, so it works over the
 * paired phone, not just WiFi); everything else is fully offline from the DB tags.
 */
@Composable
fun PoiDetailScreen(
    poi: Poi,
    hasRoute: Boolean,
    cachedDescription: String?,
    loadDescription: suspend () -> String?,
    // Google Places fallback for hours when OSM has none. Null when the feature is
    // unavailable (no API key, or category not eligible) → the button is hidden.
    cachedGoogleHours: PlacesClient.Result?,
    loadGoogleHours: (suspend () -> PlacesClient.Result?)?,
    onBack: () -> Unit,
) {
    val style = styleForType(poi.type)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Title block.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(style.color),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(style.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.size(16.dp))
                Text(
                    poi.name ?: "Unnamed",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Spacer(Modifier.height(8.dp))

            // At-a-glance route context.
            val context = buildList {
                poi.distancesAlongRoute.firstOrNull()?.let { add("at ${formatDistance(it)}") }
                if (hasRoute && poi.detourMeters > 0) add("detour ${formatDistance(poi.detourMeters)}")
            }.joinToString(" · ")
            if (context.isNotEmpty()) {
                Text(
                    context,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Opening hours: badge (open/closed + opens-at) and a weekday table from
            // OSM; when OSM has none, offer an on-demand Google lookup.
            OpeningHoursSection(poi.tags["opening_hours"], cachedGoogleHours, loadGoogleHours)
            Spacer(Modifier.height(16.dp))

            // Contact rows (wheelchair intentionally omitted — irrelevant on a bike).
            val contactRows = buildList {
                poi.tags["phone"]?.let { add("Phone" to it) }
                poi.tags["website"]?.let { add("Website" to it) }
            }
            if (contactRows.isNotEmpty()) {
                contactRows.forEach { (label, value) -> InfoRow(label, value) }
                Spacer(Modifier.height(16.dp))
            }

            // Description (on-demand, online).
            DescriptionSection(
                hasWikipedia = poi.tags.containsKey("wikipedia") || poi.tags.containsKey("wikidata"),
                osmDescription = poi.tags["description"],
                cachedDescription = cachedDescription,
                loadDescription = loadDescription,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OpeningHoursSection(
    osmHours: String?,
    cachedGoogleHours: PlacesClient.Result?,
    loadGoogleHours: (suspend () -> PlacesClient.Result?)?,
) {
    Text(
        "Opening hours",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))

    // OSM is the primary source; Google fills the gap on demand. Both normalize to the
    // same Hours, so a single renderer draws either.
    val osm = remember(osmHours) { osmHours?.let { OpeningHours.Hours.fromOsm(it) } }
    when {
        osm != null -> HoursContent(osm, viaGoogle = false)
        loadGoogleHours == null -> Text("Not available", style = MaterialTheme.typography.bodyMedium)
        else -> GoogleHoursFallback(cachedGoogleHours, loadGoogleHours)
    }
}

/** Button → live Google fetch → the same [HoursContent], with "via Google" attribution. */
@Composable
private fun GoogleHoursFallback(
    cached: PlacesClient.Result?,
    loadGoogleHours: suspend () -> PlacesClient.Result?,
) {
    // Seed from a still-fresh cached fetch so reopening shows hours instantly.
    var result by remember { mutableStateOf(cached) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when {
        result != null -> HoursContent(result!!.hours, viaGoogle = true)
        loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Checking Google…", style = MaterialTheme.typography.bodySmall)
        }
        else -> {
            Text("Not in OpenStreetMap.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                failed = false
                loading = true
                scope.launch {
                    result = loadGoogleHours()
                    failed = result == null
                    loading = false
                }
            }) {
                Text("Check hours on Google")
            }
            if (failed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Couldn't find hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The one renderer for any [OpeningHours.Hours]: badge + (24/7 line | table | raw). */
@Composable
private fun HoursContent(hours: OpeningHours.Hours, viaGoogle: Boolean) {
    val today = remember { todayIndex() }
    val status = remember(hours) { hours.status() }

    HoursBadge(status.state, status.opensAtLabel(today))
    Spacer(Modifier.height(10.dp))

    when {
        hours.is247 -> Text("Open 24/7", style = MaterialTheme.typography.bodyMedium)
        hours.rawFallback != null ->
            Text(hours.rawFallback!!, style = MaterialTheme.typography.bodyMedium)
        else -> HoursTable(schedule = hours.schedule, today = today)
    }

    if (viaGoogle) {
        Spacer(Modifier.height(6.dp))
        Text(
            "via Google",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Open/Closed badge (+ optional opens-at), shared by the OSM and Google paths. */
@Composable
private fun HoursBadge(state: OpeningHours.OpenState, opensAt: String?) {
    when (state) {
        OpeningHours.OpenState.OPEN -> Badge("Open now", OpenGreen)
        OpeningHours.OpenState.CLOSED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Badge("Closed", ClosedRed)
            opensAt?.let {
                Spacer(Modifier.size(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        OpeningHours.OpenState.UNKNOWN -> Unit
    }
}

/** A weekday × hours table; today's row is emphasized. Closed days show "Closed". */
@Composable
private fun HoursTable(schedule: Map<Int, List<OpeningHours.TimeRange>>, today: Int) {
    Column {
        for (day in 0..6) {
            val ranges = schedule[day].orEmpty()
            val isToday = day == today
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    OpeningHours.DAY_LABELS[day],
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    if (ranges.isEmpty()) "Closed" else ranges.joinToString(", ") { it.format() },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (ranges.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

@Composable
private fun DescriptionSection(
    hasWikipedia: Boolean,
    osmDescription: String?,
    cachedDescription: String?,
    loadDescription: suspend () -> String?,
) {
    // Prefer a fetched/cached Wikipedia extract; fall back to the OSM description tag.
    var fetched by remember { mutableStateOf(cachedDescription) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(hasWikipedia, cachedDescription) {
        if (hasWikipedia && fetched == null) {
            loading = true
            fetched = loadDescription()
            loading = false
        }
    }

    val text = fetched ?: osmDescription
    when {
        text != null -> {
            Text("About", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Loading description…", style = MaterialTheme.typography.bodySmall)
        }
    }
}
