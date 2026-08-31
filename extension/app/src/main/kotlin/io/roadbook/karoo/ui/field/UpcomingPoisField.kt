package io.roadbook.karoo.ui.field

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import androidx.glance.color.ColorProvider as dayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.roadbook.karoo.data.Category

/**
 * One upcoming POI cell in a category row.
 *
 * @param distance display distance ahead, e.g. `1.2km` (nearest) or `4.8` (follow-ups
 *   drop the unit to stay narrow — the DataType formats these).
 * @param arrow prefix marking position: "" nearest, "↑" 2nd, "↑↑" 3rd.
 * @param detour badge like `+200m`, or null when negligible/unknown.
 * @param detourMeters raw detour, drives the color ramp (green→amber→red).
 */
data class PoiCell(
    val distance: String,
    val arrow: String = "",
    val detour: String? = null,
    val detourMeters: Int = 0,
)

/**
 * View-model for one category's row: the category plus up to three upcoming POIs. The
 * DataType builds these off [io.roadbook.karoo.data.upcomingByCategory].
 */
data class CategoryRow(
    val category: Category,
    val name: String?,
    val cells: List<PoiCell>,
)

// The Karoo field background follows the ride theme (light OR dark), so every color
// is day/night adaptive: dark ink on the light theme, light ink on the dark theme.
// (Hardcoding white made the primary distance invisible in light mode.)
private val Primary = dayNightColorProvider(day = Color(0xFF111111), night = Color.White)
private val Dim = dayNightColorProvider(day = Color(0xFF5A5A5A), night = Color(0xFFB8B8B8))
private val Faint = dayNightColorProvider(day = Color(0xFF7A7A7A), night = Color(0xFF9A9A9A))
private val Divider = dayNightColorProvider(day = Color(0x22000000), night = Color(0x22FFFFFF))

// Detour color ramp: how far off-route you'd deviate. Green = trivial, amber = worth a
// thought, coral = a real detour. Each has a darker day variant so it stays legible on
// the light theme (pale amber/green wash out on white).
private val DetourNear = dayNightColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF66BB6A)) // green
private val DetourMid = dayNightColorProvider(day = Color(0xFFB8860B), night = Color(0xFFFFC107))  // amber
private val DetourFar = dayNightColorProvider(day = Color(0xFFD84315), night = Color(0xFFFF7043))  // coral

private fun detourColor(meters: Int): ColorProvider = when {
    meters <= 250 -> DetourNear
    meters <= 1000 -> DetourMid
    else -> DetourFar
}

/**
 * Full-size field: one row per enabled category, the rows sharing the field's height
 * evenly so they fill it. Each row is a colored accent bar + glyph + label on the
 * left and the next three distances on the right; a hairline separates rows.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun LargeUpcomingField(rows: List<CategoryRow>, activity: ComponentName) {
    // A Glance Column can hold at most 10 children (hard RemoteViews-translator limit),
    // so we must NOT add separate divider views between rows — 8 rows + 7 dividers = 15
    // would throw and silently drop the overflow. Each row draws its own top hairline
    // instead, keeping the Column child count == number of categories (≤8).
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clickable(actionStartActivity(openAppIntent(activity))),
    ) {
        rows.forEachIndexed { i, row ->
            // Each row takes an equal share of the field height (defaultWeight) so the
            // rows fill it top-to-bottom with no wasted whitespace. Safe now that the
            // parent has one child per category (≤8 < the Glance 10-child limit).
            Box(GlanceModifier.fillMaxWidth().defaultWeight()) {
                CategoryRowView(row, showDivider = i > 0)
            }
        }
    }
}

@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
private fun CategoryRowView(row: CategoryRow, showDivider: Boolean) {
    val style = styleFor(row.category)
    // The row owns its top hairline (drawn inside this row's own Column) so the parent
    // Column doesn't need extra divider children — see the 10-child limit note above.
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (showDivider) {
            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Divider)) {}
        }
        // One row per category: glyph + label on the left (flexible, so it never pushes
        // the distances off-screen), three fixed-width distance cells on the right. Each
        // cell stacks the distance over its detour badge — rows fill the height, so with
        // ≤8 categories there's room for both lines.
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 2.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(style.glyph, style = TextStyle(fontSize = 15.sp))
            Spacer(GlanceModifier.width(5.dp))
            Text(
                style.label,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = ColorProvider(style.color),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            DistCell(row.cells.getOrNull(0), primary = true)
            DistCell(row.cells.getOrNull(1), primary = false)
            DistCell(row.cells.getOrNull(2), primary = false)
        }
    }
}

@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
private fun DistCell(cell: PoiCell?, primary: Boolean) {
    // Fixed-width slot, right-aligned. Distance is NEUTRAL-colored (dark for the nearest,
    // dim for follow-ups) — a distance is a distance. The DETOUR sits below it in its own
    // severity color (green/amber/coral), so the two facts don't get conflated.
    // Keep the slot's width even when empty so distances stay column-aligned across rows,
    // but draw nothing (no `·` filler) — a category with fewer than 3 POIs ahead then reads
    // as intentional rather than padded.
    if (cell == null) {
        Spacer(GlanceModifier.width(if (primary) 58.dp else 56.dp))
        return
    }
    Column(
        // ~255dp-wide field (300dpi). Follow-ups render the `km` unit in a smaller font
        // (see [DistanceText]) so it fits without clipping.
        modifier = GlanceModifier.width(if (primary) 58.dp else 56.dp),
        horizontalAlignment = Alignment.Horizontal.End,
    ) {
        DistanceText(cell, primary)
        cell.detour?.let {
            Text(
                it,
                maxLines = 1,
                style = TextStyle(color = detourColor(cell.detourMeters), fontSize = 10.sp),
            )
        }
    }
}

/**
 * Distance with the arrow prefix and a smaller-font `km` unit. Glance can't vary font
 * size within one [Text], so the number and the unit are separate Texts in a Row.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
private fun DistanceText(cell: PoiCell, primary: Boolean) {
    val number = cell.distance.removeSuffix("km")
    val hasKm = cell.distance.endsWith("km")
    val color = if (primary) Primary else Dim
    val numSize = if (primary) 15.sp else 13.sp
    val unitSize = if (primary) 11.sp else 9.sp
    Row(verticalAlignment = Alignment.Vertical.Bottom) {
        Text(
            (if (cell.arrow.isEmpty()) "" else cell.arrow + " ") + number,
            maxLines = 1,
            style = TextStyle(
                color = color,
                fontSize = numSize,
                fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
            ),
        )
        if (hasKm) {
            Text(
                "km",
                maxLines = 1,
                style = TextStyle(color = color, fontSize = unitSize, fontWeight = FontWeight.Medium),
            )
        }
    }
}

/**
 * Small field (e.g. 1/8): a single category over three tight lines:
 *   1: glyph · LABEL
 *   2: nearest POI — distance + colored detour
 *   3: 2nd & 3rd POI (`↑`/`↑↑`)
 * Fonts are kept small so all three lines fit without clipping. The DataType picks
 * which category via its remembered index; tapping cycles it.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun SmallUpcomingField(row: CategoryRow, activity: ComponentName) {
    val style = styleFor(row.category)
    val nearest = row.cells.getOrNull(0)
    val second = row.cells.getOrNull(1)
    val third = row.cells.getOrNull(2)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clickable(actionStartActivity(openAppIntent(activity))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        // Line 1: glyph + category name.
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(style.glyph, style = TextStyle(fontSize = 13.sp))
            Spacer(GlanceModifier.width(4.dp))
            Text(
                style.label,
                style = TextStyle(
                    color = ColorProvider(style.color),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(2.dp))
        // Line 2: nearest POI — emphasized distance + colored detour.
        Row(verticalAlignment = Alignment.Vertical.Bottom) {
            Text(
                nearest?.distance ?: "–",
                style = TextStyle(color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            nearest?.detour?.let {
                Spacer(GlanceModifier.width(3.dp))
                Text(
                    it,
                    style = TextStyle(color = detourColor(nearest.detourMeters), fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        }
        // Line 3: 2nd & 3rd upcoming.
        if (second != null || third != null) {
            Spacer(GlanceModifier.height(1.dp))
            val rest = listOfNotNull(second, third)
                .joinToString("   ") { "${it.arrow} ${it.distance}".trim() }
            Text(rest, style = TextStyle(color = Dim, fontSize = 11.sp), maxLines = 1)
        }
    }
}

/** A single centered message (empty / prompt states). */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun FieldMessage(text: String) {
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 2,
        )
    }
}

/** Intent-extra key: when MainActivity sees this, it kicks off a build on launch. */
const val EXTRA_ACTION = "roadbook.field.action"
const val ACTION_BUILD = "build"

/** Intent that just opens the Roadbook app (no build), used for tap-to-open. */
private fun openAppIntent(activity: ComponentName): Intent =
    Intent()
        .setComponent(activity)
        .setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * "Build" prompt (no roadbook yet). Tapping opens the Roadbook app straight into a
 * build via [MainActivity] — no manual navigate-to-extension-then-tap dance. The
 * package/class is passed in so this stays free of a hard app dependency.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun BuildPromptField(activity: ComponentName) {
    val intent = Intent()
        .setComponent(activity)
        .setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_ACTION, ACTION_BUILD)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(6.dp)
            .clickable(actionStartActivity(intent)),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text("🗺", style = TextStyle(fontSize = 22.sp))
        Spacer(GlanceModifier.height(2.dp))
        Text(
            "Tap to build",
            style = TextStyle(color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(
            "roadbook",
            style = TextStyle(color = Dim, fontSize = 11.sp),
            maxLines = 1,
        )
    }
}

private val Amber = ColorProvider(Color(0xFFFFB300))

/**
 * Mid-ride deviation state: glyph + amber "Off route" — attention, not an error. Kept
 * visually distinct from the dim prompt states so a glance reads it instantly.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun OffRouteMessage() {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text("↝", style = TextStyle(color = Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold))
        Text(
            "Off route",
            style = TextStyle(color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}
