package com.swordai.bridge.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swordai.bridge.ui.theme.Sword

/** Анимированный фон: медленно дрейфующие синие и фиолетовые «сияния» на чёрном. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Box(modifier.fillMaxSize().background(Sword.Background)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Sword.Blue.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(w * (0.1f + 0.25f * drift), h * 0.05f),
                    radius = w * 0.95f,
                ),
                radius = w * 0.95f,
                center = Offset(w * (0.1f + 0.25f * drift), h * 0.05f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Sword.Violet.copy(alpha = 0.24f), Color.Transparent),
                    center = Offset(w * (0.95f - 0.2f * drift), h * (0.3f + 0.1f * drift)),
                    radius = w * 0.85f,
                ),
                radius = w * 0.85f,
                center = Offset(w * (0.95f - 0.2f * drift), h * (0.3f + 0.1f * drift)),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Sword.Cyan.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(w * 0.3f, h * (0.95f - 0.1f * drift)),
                    radius = w,
                ),
                radius = w,
                center = Offset(w * 0.3f, h * (0.95f - 0.1f * drift)),
            )
        }
        content()
    }
}

private val glassFill = Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color(0x08FFFFFF)))
private val glassBorder = Brush.linearGradient(
    listOf(Sword.Blue.copy(alpha = 0.45f), Sword.Violet.copy(alpha = 0.25f), Color(0x10FFFFFF)),
)

/** Стеклянная панель: полупрозрачная заливка, градиентная кромка, скруглённые углы. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    corner: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(Sword.Graphite.copy(alpha = 0.55f))
        .background(glassFill)
        .border(1.dp, glassBorder, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

/** Пульсирующий индикатор состояния. */
@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier, dotSize: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Canvas(modifier.size(dotSize * 2.4f)) {
        val r = dotSize.toPx() / 2
        if (pulsing) {
            drawCircle(color.copy(alpha = 0.35f * (1f - pulse)), radius = r * (1f + 1.4f * pulse))
        }
        drawCircle(color, radius = r)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Sword.Muted, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Небольшая метка-капсула. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(key, color = Sword.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
        Text(
            value,
            color = Sword.Text,
            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Sword.Text) else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Основная кнопка с градиентом синий → фиолетовый. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    colors: List<Color> = listOf(Sword.Blue, Sword.Violet),
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(if (enabled) colors else colors.map { it.copy(alpha = 0.35f) }))
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Sword.Text) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .border(1.dp, Sword.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Строка настройки с переключателем. */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Sword.Blue,
                uncheckedThumbColor = Sword.Muted,
                uncheckedTrackColor = Sword.GraphiteHigh,
                uncheckedBorderColor = Sword.Line,
            ),
        )
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String?, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal, modifier = Modifier.padding(vertical = 6.dp))
}
