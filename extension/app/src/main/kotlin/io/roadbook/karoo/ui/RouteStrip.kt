package io.roadbook.karoo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.roadbook.karoo.data.Poi

/**
 * The route distance strip from the mockup: a horizontal line spanning the route,
 * with a colored dot per POI at its position along the route. Purely a visual
 * overview — the list below is the interactive part.
 */
@Composable
fun RouteStrip(
    pois: List<Poi>,
    routeLengthMeters: Double,
    modifier: Modifier = Modifier,
) {
    val lineColor = Color(0xFFDDDDDD)
    val endColor = Color(0xFF222222)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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

        if (routeLengthMeters <= 0.0) return@Canvas

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
}
