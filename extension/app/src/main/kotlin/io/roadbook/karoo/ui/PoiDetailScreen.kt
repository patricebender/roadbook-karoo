package io.roadbook.karoo.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.roadbook.karoo.data.OpeningHours
import io.roadbook.karoo.data.PlacesClient
import io.roadbook.karoo.data.Poi
import kotlinx.coroutines.launch

// Neutral tint for the "hours exist but are seasonal/complex" badge.
private val SeasonalGrey = Color(0xFF757575)

/**
 * Place detail, laid out as a centered hero (icon + name + type) followed by grouped
 * cards: an at-a-glance card (open/closed + street address + phone), the weekday hours
 * table, a centered website QR, and an on-demand Wikipedia description.
 *
 * The description is loaded via [loadDescription] (routed through the Karoo HTTP bridge,
 * so it works over the paired phone, not just WiFi); everything else is fully offline
 * from the DB tags. Google Places fills in hours on demand when OSM has none.
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
    val context = LocalContext.current
    val address = remember(poi.tags) { formatAddress(poi.tags) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Back arrow gets its own row so the hero below can center cleanly.
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Centered hero: colored category disc, name, then the human type label.
            Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).background(style.color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(style.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                poi.name ?: "Unnamed",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                style.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Route context as centered chips (distance along / detour). Absent off-route.
            val chips = buildList {
                poi.distancesAlongRoute.firstOrNull()?.let { add("at ${formatDistance(it)}") }
                if (hasRoute && poi.detourMeters > 0) add("detour ${formatDistance(poi.detourMeters)}")
            }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { OutlineChip(it) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // At-a-glance card: open/closed status, then where it is and how to call.
            InfoCard {
                OpeningHoursStatus(poi.tags["opening_hours"], cachedGoogleHours, loadGoogleHours)
                address?.let {
                    Spacer(Modifier.height(10.dp))
                    IconTextRow(Icons.Filled.Place, it)
                }
                poi.tags["phone"]?.let {
                    Spacer(Modifier.height(10.dp))
                    IconTextRow(Icons.Filled.Call, it)
                }
            }

            // Full weekday hours table, when we have a structured schedule.
            HoursTableSection(poi.tags["opening_hours"], cachedGoogleHours)

            // Website as a centered, scannable QR.
            poi.tags["website"]?.let {
                Spacer(Modifier.height(16.dp))
                WebsiteQr(it, context)
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

/**
 * The status line inside the at-a-glance card: the open/closed (or seasonal) badge from
 * OSM, or an on-demand Google lookup when OSM has none. Just the badge — the full
 * weekday table lives in its own section below.
 */
@Composable
private fun OpeningHoursStatus(
    osmHours: String?,
    cachedGoogleHours: PlacesClient.Result?,
    loadGoogleHours: (suspend () -> PlacesClient.Result?)?,
) {
    val osm = remember(osmHours) { osmHours?.let { OpeningHours.Hours.fromOsm(it) } }
    when {
        osm != null -> HoursSummary(osm, viaGoogle = false)
        loadGoogleHours == null -> Text(
            "Hours not available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> GoogleHoursFallback(cachedGoogleHours, loadGoogleHours)
    }
}

/** Badge + optional "opens …" / "Open 24/7" line, shared by OSM and Google. */
@Composable
private fun HoursSummary(hours: OpeningHours.Hours, viaGoogle: Boolean) {
    val today = remember { todayIndex() }
    val status = remember(hours) { hours.status() }

    if (hours.rawFallback != null) {
        Badge("Seasonal", SeasonalGrey)
        Spacer(Modifier.height(6.dp))
        Text(hours.rawFallback!!, style = MaterialTheme.typography.bodyMedium)
    } else {
        HoursBadge(status.state, status.opensAtLabel(today))
        if (hours.is247) {
            Spacer(Modifier.height(6.dp))
            Text("Open 24/7", style = MaterialTheme.typography.bodyMedium)
        }
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

/** Button → live Google fetch → the same [HoursSummary], with "via Google" attribution. */
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
        result != null -> HoursSummary(result!!.hours, viaGoogle = true)
        loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Checking Google…", style = MaterialTheme.typography.bodySmall)
        }
        else -> {
            Text(
                "Not in OpenStreetMap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * The weekday × hours table, in its own card under an "Opening hours" label. Only shown
 * when OSM (or an already-fetched Google result) gives a structured schedule — the card
 * is skipped for 24/7, seasonal, or unknown cases, whose summary lives in the card above.
 */
@Composable
private fun HoursTableSection(osmHours: String?, cachedGoogleHours: PlacesClient.Result?) {
    val hours = remember(osmHours, cachedGoogleHours) {
        osmHours?.let { OpeningHours.Hours.fromOsm(it) } ?: cachedGoogleHours?.hours
    }
    // A table only makes sense for a plain weekday schedule.
    if (hours == null || hours.is247 || hours.rawFallback != null) return
    if (hours.schedule.isEmpty()) return

    Spacer(Modifier.height(16.dp))
    SectionLabel("Opening hours")
    Spacer(Modifier.height(6.dp))
    InfoCard {
        HoursTable(schedule = hours.schedule, today = remember { todayIndex() })
    }
}

/**
 * Open/Closed badge. When closed, the "opens …" text drops to its own line so it stays
 * readable on the narrow display.
 */
@Composable
private fun HoursBadge(state: OpeningHours.OpenState, opensAt: String?) {
    when (state) {
        OpeningHours.OpenState.OPEN -> Badge("Open now", OpenGreen)
        OpeningHours.OpenState.CLOSED -> Column {
            Badge("Closed", ClosedRed)
            opensAt?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        OpeningHours.OpenState.UNKNOWN -> Text(
            "Hours unknown",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** A small outlined pill for route context (distance along / detour). */
@Composable
private fun OutlineChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A rounded panel grouping related detail rows against a subtle tinted background. */
@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

/** A leading icon + value row, shared by the address and phone lines. */
@Composable
private fun IconTextRow(icon: ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Assemble a human street address from OSM `addr:*` tags, e.g.
 * "Mannheimer Straße 12, 76133 Karlsruhe". Returns null when there's no street or
 * house number — the row is then hidden (typical for water/toilets).
 */
private fun formatAddress(tags: Map<String, String>): String? {
    val street = tags["addr:street"]
    val houseNumber = tags["addr:housenumber"]
    if (street == null && houseNumber == null) return null

    val line1 = listOfNotNull(street, houseNumber).joinToString(" ")
    val line2 = listOfNotNull(tags["addr:postcode"], tags["addr:city"]).joinToString(" ")
    return listOf(line1, line2).filter { it.isNotBlank() }.joinToString(", ")
}

/**
 * Website as a centered QR code: far easier to open on a phone than reading and typing a
 * URL off the Karoo. Tapping it still opens the link directly if a browser is reachable.
 */
@Composable
private fun WebsiteQr(url: String, context: android.content.Context) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SectionLabel("Website")
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(10.dp),
        ) {
            Image(
                painter = rememberQrCodePainter(url),
                contentDescription = "QR code for $url",
                modifier = Modifier
                    .size(140.dp)
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
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
            Spacer(Modifier.height(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionLabel("About")
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        loading -> {
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading description…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
