package hr.sonicpulse.app.ui.monitoring

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hr.sonicpulse.app.R
import hr.sonicpulse.app.ui.components.AppCard
import hr.sonicpulse.app.ui.components.SectionHeader
import hr.sonicpulse.app.ui.theme.MonospaceValueStyle

private const val RING_MIN_DBFS = -60f
private const val RING_MAX_DBFS = 0f
private const val RING_START_ANGLE = -225f
private const val RING_SWEEP_ANGLE = 270f

/** dBFS ring (design spec §5.1A) — a 270° arc gauge with a pulsing glow while monitoring is active. */
@Composable
fun DbfsRing(currentDbfs: Float, active: Boolean, modifier: Modifier = Modifier) {
    // No infiniteRepeatable at all while inactive — an idle ring must not keep an animation (and
    // its recompositions) running forever in the background; the glow isn't drawn then either.
    val glowAlpha = if (active) {
        val transition = rememberInfiniteTransition(label = "dbfsRingGlow")
        val animatedGlowAlpha by transition.animateFloat(
            initialValue = 0.05f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dbfsRingGlowAlpha"
        )
        animatedGlowAlpha
    } else {
        0.05f
    }

    val trackColor = MaterialTheme.colorScheme.outline
    val valueColor = MaterialTheme.colorScheme.primary
    val strokeWidthPx = with(LocalDensity.current) { 6.dp.toPx() }

    Box(modifier = modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(168.dp)) {
            if (active) {
                drawCircle(
                    brush = Brush.radialGradient(listOf(valueColor.copy(alpha = glowAlpha), Color.Transparent)),
                    radius = size.minDimension / 2f
                )
            }
            val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            val arcSize = Size(size.width - strokeWidthPx, size.height - strokeWidthPx)
            val topLeft = Offset(strokeWidthPx / 2f, strokeWidthPx / 2f)
            drawArc(
                color = trackColor,
                startAngle = RING_START_ANGLE,
                sweepAngle = RING_SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
            val valueSweep = (currentDbfs.coerceIn(RING_MIN_DBFS, RING_MAX_DBFS) - RING_MIN_DBFS) /
                (RING_MAX_DBFS - RING_MIN_DBFS) * RING_SWEEP_ANGLE
            drawArc(
                color = valueColor,
                startAngle = RING_START_ANGLE,
                sweepAngle = valueSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "%.1f".format(currentDbfs),
                style = MonospaceValueStyle.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
                color = valueColor
            )
            Text(
                text = stringResource(R.string.unit_dbfs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

private const val CHART_POINT_COUNT = 100
private const val CHART_MIN_DBFS = -60f
private const val CHART_MAX_DBFS = 0f

/** Live dBFS history chart (design spec §5.1E) — a pure renderer, does not animate or drive itself. */
@Composable
fun LiveDbfsChart(currentDbfs: Float, dbfsHistory: List<Float>, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionHeader(stringResource(R.string.section_live_dbfs))
            Text(
                text = "%.1f".format(currentDbfs),
                style = MonospaceValueStyle,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(80.dp)) {
            ChartCanvas(dbfsHistory, modifier = Modifier.fillMaxWidth().height(80.dp))

            val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            val axisLabelStyle = MonospaceValueStyle.copy(fontSize = 9.sp)
            Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "-20", "-40", "-60").forEach { label ->
                    Text(text = label, style = axisLabelStyle, color = dim)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.chart_axis_10s_ago), style = axisLabelStyle, color = dim)
                Text(stringResource(R.string.chart_axis_5s_ago), style = axisLabelStyle, color = dim)
                Text(stringResource(R.string.chart_axis_now), style = axisLabelStyle, color = dim)
            }
        }
    }
}

@Composable
private fun ChartCanvas(dbfsHistory: List<Float>, modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.outline
    val lineColor = MaterialTheme.colorScheme.primary
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
    val lineStrokeWidthPx = with(LocalDensity.current) { 2.dp.toPx() }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        fun yFor(dbfs: Float): Float {
            val clamped = dbfs.coerceIn(CHART_MIN_DBFS, CHART_MAX_DBFS)
            val fraction = (clamped - CHART_MIN_DBFS) / (CHART_MAX_DBFS - CHART_MIN_DBFS)
            return height - fraction * height
        }

        listOf(-20f, -40f).forEach { gridValue ->
            val y = yFor(gridValue)
            drawLine(color = gridColor, start = Offset(0f, y), end = Offset(width, y), pathEffect = dashEffect)
        }

        if (dbfsHistory.isEmpty()) return@Canvas

        // The list may hold fewer than CHART_POINT_COUNT samples early in a session — those
        // points are anchored to the right edge ("now"), not stretched across the full width.
        val startSlot = CHART_POINT_COUNT - dbfsHistory.size
        fun xFor(slot: Int) = slot.toFloat() / (CHART_POINT_COUNT - 1) * width

        val linePath = Path()
        dbfsHistory.forEachIndexed { index, value ->
            val point = Offset(xFor(startSlot + index), yFor(value))
            if (index == 0) linePath.moveTo(point.x, point.y) else linePath.lineTo(point.x, point.y)
        }
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = lineStrokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(xFor(startSlot + dbfsHistory.lastIndex), height)
            lineTo(xFor(startSlot), height)
            close()
        }
        drawPath(path = fillPath, brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.3f), Color.Transparent)))
    }
}
