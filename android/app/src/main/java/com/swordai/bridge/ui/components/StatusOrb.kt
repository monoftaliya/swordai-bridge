package com.swordai.bridge.ui.components

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.swordai.bridge.agent.LinkStatus
import com.swordai.bridge.ui.theme.Sword

/** Светящийся индикатор соединения с расходящимися волнами. */
@Composable
fun StatusOrb(color: Color, active: Boolean, modifier: Modifier = Modifier.size(88.dp)) {
    val transition = rememberInfiniteTransition(label = "orb")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "wave",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "breathe",
    )
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2
        if (active) {
            for (k in 0..1) {
                val p = (wave + k * 0.5f) % 1f
                drawCircle(
                    color.copy(alpha = 0.32f * (1f - p)),
                    radius = r * (0.55f + 0.45f * p),
                    center = c,
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        val glow = r * 0.62f * breathe
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.02f)), center = c, radius = glow),
            radius = glow,
            center = c,
        )
        drawCircle(
            Brush.radialGradient(listOf(Color.White, color), center = c, radius = r * 0.24f),
            radius = r * 0.22f,
            center = c,
        )
    }
}

object UiFormat {
    fun ago(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return "никогда"
        val seconds = (System.currentTimeMillis() - timestamp) / 1000
        return when {
            seconds < 5 -> "только что"
            seconds < 60 -> "$seconds с назад"
            seconds < 3600 -> "${seconds / 60} мин назад"
            seconds < 86_400 -> "${seconds / 3600} ч назад"
            else -> "${seconds / 86_400} дн назад"
        }
    }

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "меньше минуты"
            minutes < 60 -> "$minutes мин"
            else -> "${minutes / 60} ч ${minutes % 60} мин"
        }
    }

    fun statusText(status: LinkStatus): String = when (status) {
        LinkStatus.CONNECTED -> "Подключено"
        LinkStatus.CONNECTING -> "Подключение…"
        LinkStatus.RECONNECTING -> "Переподключение…"
        LinkStatus.OFFLINE -> "Нет сети"
        LinkStatus.UNAUTHORIZED -> "Нужно сопряжение"
        LinkStatus.KEY_MISMATCH -> "Ключ сервера изменён"
        LinkStatus.DISABLED -> "Отключено"
    }

    fun statusColor(status: LinkStatus): Color = when (status) {
        LinkStatus.CONNECTED -> Sword.Success
        LinkStatus.CONNECTING, LinkStatus.RECONNECTING -> Sword.Warning
        LinkStatus.OFFLINE -> Sword.Warning
        LinkStatus.UNAUTHORIZED, LinkStatus.KEY_MISMATCH -> Sword.Danger
        LinkStatus.DISABLED -> Sword.Faint
    }

    fun commandColor(status: String): Color = when (status) {
        "ok" -> Sword.Success
        "error" -> Sword.Danger
        "rejected" -> Sword.Warning
        "awaiting" -> Sword.Violet
        else -> Sword.Blue
    }

    fun commandStatusText(status: String): String = when (status) {
        "ok" -> "выполнено"
        "error" -> "ошибка"
        "rejected" -> "отклонено"
        "awaiting" -> "ждёт подтверждения"
        "running" -> "выполняется"
        else -> "в очереди"
    }
}
