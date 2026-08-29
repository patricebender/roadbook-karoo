package io.roadbook.karoo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

// Roadbook brand colours (match the mark drawable).
private val RouteStart = Color(0xFFFF5E3A)
private val RouteEnd = Color(0xFFFFA24A)
private val PinTop = Color(0xFFFF7A45)
private val PinBottom = Color(0xFFF0421B)

// The mark artwork lives in a 372x392 space (roadbook-mark-compact, origin-shifted).
// We describe the route + node positions here and scale to the canvas at draw time.
private const val ART_W = 372f
private const val ART_H = 392f

// Route control points in art space (same curve as ic_roadbook_mark).
private val routePath = Path().apply {
    moveTo(24f, 358f)
    cubicTo(100f, 266f, 78f, 212f, 166f, 204f)
    // "S 272 186 312 108" — smooth cubic; reflected control of the previous is (254,196).
    cubicTo(254f, 196f, 272f, 186f, 312f, 108f)
}

// Fractional positions (0..1 along the route) of the two passed waypoints. The pin
// sits at the end (1.0) and is drawn separately.
private val NODE_FRACTIONS = floatArrayOf(0.34f, 0.70f)

/**
 * Animated roadbook loading mark: the route is traced by a bright travelling head that
 * loops from start to the destination pin; each passed waypoint lights up as the head
 * reaches it, and the pin gives a soft pulse at the top of every loop. Used as the
 * centrepiece of the build/"searching" state so the wait reads as the roadbook being
 * traced along the route.
 */
@Composable
fun RoadbookLoadingLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val transition = rememberInfiniteTransition(label = "roadbook-loading")
    // One full trace of the route per cycle, with a brief hold at the end (via the
    // easing tail) before it restarts.
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress",
    )

    Canvas(modifier = modifier.size(size)) {
        drawRoadbook(progress)
    }
}

private fun DrawScope.drawRoadbook(progress: Float) {
    // Scale the art-space path into the canvas (uniform, centred).
    val scale = min(this.size.width / ART_W, this.size.height / ART_H)
    val dx = (this.size.width - ART_W * scale) / 2f
    val dy = (this.size.height - ART_H * scale) / 2f

    val path = Path().apply {
        addPath(routePath)
        transform(
            androidx.compose.ui.graphics.Matrix().apply {
                translate(dx, dy)
                scale(scale, scale)
            },
        )
    }

    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    val stroke = 30f * scale

    val routeBrush = Brush.linearGradient(
        colors = listOf(RouteStart, RouteEnd),
        start = Offset(dx + 45f * scale, dy + ART_H * scale),
        end = Offset(dx + 316f * scale, dy),
    )

    // Dim base route (the "unvisited" road), then the bright traced portion on top.
    drawPath(path, brush = routeBrush, alpha = 0.28f, style = Stroke(width = stroke, cap = StrokeCap.Round))

    val headLen = length * progress
    if (headLen > 0f) {
        val traced = Path()
        measure.getSegment(0f, headLen, traced, true)
        drawPath(traced, brush = routeBrush, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }

    // Passed waypoints: solid white nodes that "pop" as the head reaches them.
    for (frac in NODE_FRACTIONS) {
        val pos = measure.getPosition(length * frac)
        val reached = progress >= frac
        // Pop window: a short scale-up right as the head arrives, settling to 1.0.
        val since = progress - frac
        val pop = if (since in 0f..0.12f) 1f + (0.12f - since) / 0.12f * 0.6f else 1f
        val baseR = 16f * scale
        val alpha = if (reached) 1f else 0.35f
        // Soft halo on the freshly-reached node.
        if (since in 0f..0.18f) {
            drawCircle(Color.White, radius = baseR * (1.8f + since * 2f), center = pos, alpha = 0.12f)
        }
        drawCircle(Color.White, radius = baseR * pop, center = pos, alpha = alpha)
    }

    // Travelling head: a bright dot riding the tip of the traced route.
    if (progress > 0f && progress < 1f) {
        val head = measure.getPosition(headLen)
        drawCircle(Color.White, radius = stroke * 0.42f, center = head, alpha = 0.9f)
        drawCircle(RouteEnd, radius = stroke * 0.42f, center = head, alpha = 0.35f)
    }

    // Destination pin at the route end. Pulses as the head arrives / on loop restart.
    val pinPos = measure.getPosition(length)
    val arrival = progress // near 1.0 → arriving
    val pinPop = when {
        arrival >= 0.9f -> 1f + (arrival - 0.9f) / 0.1f * 0.18f
        else -> 1f
    }
    val pinReached = progress >= 0.94f
    val pinR = 42f * scale * pinPop
    val pinBrush = Brush.verticalGradient(
        colors = listOf(PinTop, PinBottom),
        startY = pinPos.y - pinR,
        endY = pinPos.y + pinR,
    )
    val pinAlpha = if (pinReached) 1f else 0.5f
    // Arrival halo.
    if (pinReached) {
        drawCircle(PinTop, radius = pinR * 1.7f, center = pinPos, alpha = 0.14f)
    }
    // Pin head (circle) + tail (triangle), scaled from art space around pinPos.
    drawCircle(brush = pinBrush, radius = pinR, center = pinPos, alpha = pinAlpha)
    val tail = Path().apply {
        val t = 26f * scale * pinPop // half-width of the tail base
        val h = 46f * scale * pinPop // tail drop below the circle centre
        moveTo(pinPos.x - t, pinPos.y + pinR * 0.62f)
        lineTo(pinPos.x, pinPos.y + h)
        lineTo(pinPos.x + t, pinPos.y + pinR * 0.62f)
        close()
    }
    drawPath(tail, brush = pinBrush, alpha = pinAlpha)
    // White eye.
    drawCircle(Color.White, radius = 17f * scale * pinPop, center = pinPos, alpha = pinAlpha)
}
