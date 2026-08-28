package io.roadbook.karoo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.roadbook.karoo.data.Poi

/**
 * The route distance strip: a horizontal line spanning the route with a colored dot
 * per POI at its position along the route, plus start/end distance labels underneath
 * so the row doubles as the route's at-a-glance summary. Purely visual — the list
 * below is the interactive part.
 */
@Composable
fun RouteStrip(
    pois: List<Poi>,
    routeLengthMeters: Double,
    modifier: Modifier = Modifier,
) {
    val lineColor = Color(0xFFDDDDDD)
    val endColor = Color(0xFF222222)
    val hasRoute = routeLengthMeters > 0.0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
        ) {
            val y = size.height / 2f
            val left = 0f
            val right = size.width
            val lineWidth = 3.dp.toPx()

            // The route line.
            drawLine(
                color = lineColor,
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = lineWidth,
            )

            // Start/finish caps.
            drawCircle(endColor, radius = 5.dp.toPx(), center = Offset(left, y))
            drawCircle(endColor, radius = 5.dp.toPx(), center = Offset(right, y))

            if (!hasRoute) return@Canvas

            // POI dots positioned by along-route distance.
            val dotRadius = 4.dp.toPx()
            for (poi in pois) {
                val along = poi.distancesAlongRoute.firstOrNull() ?: continue
                val frac = (along / routeLengthMeters).coerceIn(0.0, 1.0).toFloat()
                val x = left + frac * (right - left)
                drawCircle(
                    color = styleForType(poi.type).color,
                    radius = dotRadius,
                    center = Offset(x, y),
                )
            }
        }

        // Under the timeline: "Start" and the total distance at the ends, with the
        // place count centered between them. Only shown when there's a route (nearby
        // builds have no along-route axis). A Box keeps the count centered on the strip
        // regardless of the side labels' widths.
        if (hasRoute) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    "Start",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                Text(
                    "${pois.size} places",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                Text(
                    formatDistance(routeLengthMeters),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}
