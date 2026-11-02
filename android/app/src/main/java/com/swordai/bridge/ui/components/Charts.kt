package com.swordai.bridge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swordai.bridge.ui.theme.Sword
import kotlin.math.max

/** Плавный график задержки соединения с градиентной заливкой. */
@Composable
fun LatencyChart(samples: List<Long>, modifier: Modifier = Modifier, height: Dp = 96.dp) {
    var revealTarget by remember { mutableStateOf(0f) }
    val reveal by animateFloatAsState(revealTarget, tween(900), label = "reveal")
    LaunchedEffect(Unit) { revealTarget = 1f }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        // сетка
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(Sword.Line, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (samples.size < 2) return@Canvas
        val maxValue = max(samples.max().toFloat() * 1.2f, 50f)
        val step = w / (samples.size - 1)
        val points = samples.mapIndexed { i, v -> Offset(i * step, h - (v / maxValue) * h * reveal) }
        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val p0 = points[i - 1]
                val p1 = points[i]
                val mid = (p0.x + p1.x) / 2
                cubicTo(mid, p0.y, mid, p1.y, p1.x, p1.y)
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(Sword.Blue.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(
            line,
            Brush.horizontalGradient(listOf(Sword.Cyan, Sword.Blue, Sword.Violet)),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
        val last = points.last()
        drawCircle(Sword.Violet.copy(alpha = 0.3f), radius = 7.dp.toPx(), center = last)
        drawCircle(Color.White, radius = 3.dp.toPx(), center = last)
    }
}

/** Столбики активности: команды по минутам. */
@Composable
fun ActivityBars(values: List<Int>, modifier: Modifier = Modifier, height: Dp = 64.dp) {
    var revealTarget by remember { mutableStateOf(0f) }
    val reveal by animateFloatAsState(revealTarget, tween(900), label = "bars")
    LaunchedEffect(Unit) { revealTarget = 1f }
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val maxValue = max(values.max(), 3).toFloat()
        val slot = size.width / values.size
        val barWidth = slot * 0.62f
        val radius = CornerRadius(barWidth / 2, barWidth / 2)
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barWidth) / 2
            val barHeight = if (v == 0) 3.dp.toPx() else max(6.dp.toPx(), size.height * (v / maxValue) * reveal)
            val brush = if (v == 0) {
                Brush.verticalGradient(listOf(Sword.Line, Sword.Line))
            } else {
                Brush.verticalGradient(listOf(Sword.Violet, Sword.Blue))
            }
            drawRoundRect(brush, topLeft = Offset(x, size.height - barHeight), size = Size(barWidth, barHeight), cornerRadius = radius)
        }
    }
}
