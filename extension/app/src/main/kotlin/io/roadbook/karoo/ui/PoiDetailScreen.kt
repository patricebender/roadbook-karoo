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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
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

            // Hours are resolved once, here, so the status pill and the hours block agree
            // and both reflect a Google lookup once it lands. OSM is primary; the Google
            // result (seeded from cache, updated by the fetch button) fills the gap.
            val osmHours = remember(poi.tags) {
                poi.tags["opening_hours"]?.let { OpeningHours.Hours.fromOsm(it) }
            }
            var googleHours by remember(poi.id) { mutableStateOf(cachedGoogleHours) }
            val hours = osmHours ?: googleHours?.hours

            // Contact fields are source-agnostic: OSM first, then the Google result once
            // it's fetched — so the page looks the same however the data arrived.
            val address = remember(poi.tags, googleHours) {
                formatAddress(poi.tags) ?: googleHours?.address
            }
            val website = poi.tags["website"] ?: googleHours?.website
            val phone = poi.tags["phone"] ?: googleHours?.phone

            // Status pill sits centered on its own line, above the route context pills,
            // so a long "Open 24/7" + "at 12.3 km" + "detour 400 m" never crowds one row.
            val statusPill = remember(hours) { statusPillFor(hours) }
            statusPill?.let {
                Spacer(Modifier.height(10.dp))
                Pill(it.text, it.color, Color.White)
            }
            val routePills = buildList {
                poi.distancesAlongRoute.firstOrNull()?.let { add("at ${formatDistance(it)}") }
                if (hasRoute && poi.detourMeters > 0) add("detour ${formatDistance(poi.detourMeters)}")
            }
            if (routePills.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    routePills.forEach { OutlineChip(it) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Opening hours: the "opens…"/24-7 line, the weekday table, or the on-demand
            // Google lookup when OSM has none.
            OpeningHoursBlock(
                osmHours = osmHours,
                googleHours = googleHours,
                onGoogleHours = { googleHours = it },
                loadGoogleHours = loadGoogleHours,
            )

            // Address + phone.
            if (address != null || phone != null) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("Contact")
                Spacer(Modifier.height(6.dp))
                InfoCard {
                    address?.let { IconTextRow(Icons.Filled.Place, it) }
                    phone?.let {
                        if (address != null) Spacer(Modifier.height(10.dp))
                        IconTextRow(Icons.Filled.Call, it)
                    }
                }
            }

            // Website as a scannable QR, in the same block style.
            website?.let {
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
 * The Opening hours block: an "Opening hours" section label over a card holding the
 * details — the weekday table, the "opens …" line for a closed place, a plain line for
 * 24/7 or seasonal, or the on-demand Google lookup when OSM has none. The open/closed
 * headline itself now lives in the hero pill row, so this block is detail-only.
 */
@Composable
private fun OpeningHoursBlock(
    osmHours: OpeningHours.Hours?,
    googleHours: PlacesClient.Result?,
    onGoogleHours: (PlacesClient.Result?) -> Unit,
    loadGoogleHours: (suspend () -> PlacesClient.Result?)?,
) {
    // Nothing to show and no way to look it up → skip the block entirely.
    if (osmHours == null && loadGoogleHours == null) return

    SectionLabel("Opening hours")
    Spacer(Modifier.height(6.dp))
    InfoCard {
        when {
            osmHours != null -> HoursDetail(osmHours, viaGoogle = false)
            googleHours != null -> HoursDetail(googleHours.hours, viaGoogle = true)
            else -> GoogleHoursFallback(loadGoogleHours!!, onGoogleHours)
        }
    }
}

/** The hours detail: weekday table, or the "opens…"/24-7/seasonal line, + attribution. */
@Composable
private fun HoursDetail(hours: OpeningHours.Hours, viaGoogle: Boolean) {
    val today = remember { todayIndex() }
    val status = remember(hours) { hours.status() }

    when {
        hours.is247 -> Text("Open 24 hours, every day", style = MaterialTheme.typography.bodyMedium)
        hours.rawFallback != null ->
            Text(hours.rawFallback!!, style = MaterialTheme.typography.bodyMedium)
        // Resolved but no hours on record (e.g. Google had none) → a clean centered
        // "not listed" note, never a false table or a "Closed" claim. Terminal: we've
        // already looked, so no action here. The "via Google" footer is redundant with
        // the copy, so it's suppressed below for this case.
        hours.unknown || hours.schedule.isEmpty() -> UnknownHoursNote(viaGoogle)
        else -> {
            // When closed now, lead with a centered "opens …" callout, then the full week.
            if (status.state == OpeningHours.OpenState.CLOSED) {
                status.opensAtLabel(today)?.let {
                    OpensCallout(it)
                    Spacer(Modifier.height(10.dp))
                }
            }
            HoursTable(schedule = hours.schedule, today = today)
            // We parsed a base week but dropped month-scoped variants — say so, so the
            // table isn't mistaken for the complete year-round picture.
            if (hours.seasonal) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hours may vary seasonally.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (viaGoogle) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "via Google",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Hours not listed" — the honest terminal state when a source resolved the place but
 * carries no usable hours. Centered, muted, no open/closed claim; a [viaGoogle] flag
 * notes we already checked Google so the rider knows there's nothing more to try.
 */
@Composable
private fun UnknownHoursNote(viaGoogle: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Hours not listed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (viaGoogle) {
            Spacer(Modifier.height(2.dp))
            Text(
                "Google has none for this place",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The "opens Mon 08:00" reopen time for a currently-closed place, as a centered soft
 * callout: a clock glyph, a muted "opens" lead-in, and the day/time emphasized. Reads as
 * the one thing a closed rider wants to know, without shouting.
 */
@Composable
private fun OpensCallout(label: String) {
    // Split the leading "opens " so the day+time can be weighted differently.
    val (lead, rest) = label.split(" ", limit = 2).let {
        if (it.size == 2) it[0] to it[1] else "opens" to label
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ClosedRed.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Schedule,
            contentDescription = null,
            tint = ClosedRed,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "$lead ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            rest,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Button → live Google fetch. The result is lifted to the parent via [onGoogleHours] so
 * the status pill updates too; the parent then re-renders this block as [HoursDetail].
 */
@Composable
private fun GoogleHoursFallback(
    loadGoogleHours: suspend () -> PlacesClient.Result?,
    onGoogleHours: (PlacesClient.Result?) -> Unit,
) {
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val fetch: () -> Unit = {
        failed = false
        loading = true
        scope.launch {
            val result = loadGoogleHours()
            onGoogleHours(result)
            failed = result == null
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            loading -> {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Searching Google…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Nothing came back from Google either — say so plainly and offer one more try.
            failed -> {
                Text(
                    "Google doesn't list hours for this place.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = fetch) { Text("Search again") }
            }

            // First view: OSM has no hours; offer a single, explicit Google lookup.
            else -> {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "No opening hours on record",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Check Google for this place",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = fetch) {
                    Icon(
                        Icons.Filled.TravelExplore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Search Google")
                }
            }
        }
    }
}

/** A color-coded status headline for the hero pill row, derived from resolved hours. */
private data class StatusPill(val text: String, val color: Color)

/** Resolve the open/closed/seasonal/24-7 pill from already-parsed [hours]. */
private fun statusPillFor(hours: OpeningHours.Hours?): StatusPill? {
    if (hours == null) return null
    if (hours.rawFallback != null) return StatusPill("Seasonal", SeasonalGrey)
    if (hours.is247) return StatusPill("Open 24/7", OpenGreen)
    return when (hours.status().state) {
        OpeningHours.OpenState.OPEN -> StatusPill("Open now", OpenGreen)
        OpeningHours.OpenState.CLOSED -> StatusPill("Closed", ClosedRed)
        OpeningHours.OpenState.UNKNOWN -> null
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
                verticalAlignment = Alignment.Top,
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
                // Each window on its own line, right-aligned: a split day
                // ("08:00–12:00, 13:00–18:00") won't collide with the day label
                // or wrap awkwardly on the narrow screen.
                Column(horizontalAlignment = Alignment.End) {
                    if (ranges.isEmpty()) {
                        Text(
                            "Closed",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        for (r in ranges) {
                            Text(
                                r.format(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A filled, color-coded pill (e.g. the open/closed status headline). */
@Composable
private fun Pill(text: String, background: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}

/** A neutral pill for route context (distance along / detour). */
@Composable
private fun OutlineChip(text: String) =
    Pill(text, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)

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
 * Website block: a scannable QR (far easier to open on a phone than typing a URL off the
 * Karoo) plus the URL, centered inside the same card style as the other blocks. Tapping
 * the QR still opens the link directly if a browser is reachable.
 */
@Composable
private fun WebsiteQr(url: String, context: android.content.Context) {
    SectionLabel("Website")
    Spacer(Modifier.height(6.dp))
    InfoCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
            Spacer(Modifier.height(8.dp))
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
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
