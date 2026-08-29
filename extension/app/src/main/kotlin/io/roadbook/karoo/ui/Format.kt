package io.roadbook.karoo.ui

import androidx.compose.ui.graphics.Color
import io.roadbook.karoo.data.OpeningHours
import io.roadbook.karoo.data.Poi
import java.util.Calendar

/**
 * Resolve a POI's normalized [OpeningHours.Hours] from whichever source we have:
 * OSM tags first, else an already-fetched Google result ([googleHours]). Returns null
 * when neither is available — the caller shows type only, no open/closed claim.
 */
fun hoursFor(poi: Poi, googleHours: OpeningHours.Hours?): OpeningHours.Hours? =
    poi.tags["opening_hours"]?.let { OpeningHours.Hours.fromOsm(it) } ?: googleHours

/** Human distance: "180 m" under 1 km, "1.6 km" above. */
fun formatDistance(meters: Int): String =
    if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "$meters m"

fun formatDistance(meters: Double): String = formatDistance(meters.toInt())

// Shared status colors (open = green, closed = muted red), used by list + detail.
val OpenGreen = Color(0xFF2E7D32)
val ClosedRed = Color(0xFFB00020)

// Neutral tint for the "hours exist but are seasonal/complex" badge/chip.
val SeasonalGrey = Color(0xFF757575)

/** Current weekday as an OpeningHours day index (0=Mon … 6=Sun). */
fun todayIndex(now: Calendar = Calendar.getInstance()): Int =
    when (now.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 0
        Calendar.TUESDAY -> 1
        Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3
        Calendar.FRIDAY -> 4
        Calendar.SATURDAY -> 5
        else -> 6
    }
