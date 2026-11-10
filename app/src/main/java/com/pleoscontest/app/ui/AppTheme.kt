package com.pleoscontest.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * iOS 27의 Liquid Glass 느낌을 옮긴 디자인 토큰과 부품.
 * 색은 Apple 시스템 색(라이트·다크)에 맞췄다. 뒤를 흐리는 효과는 차량 화면 가독성과 성능을 생각해 쓰지 않고,
 * 떠 있는 면, 얇은 테두리, 부드러운 그림자로 대신한다. 반투명 면은 그림자가 비쳐 얼룩이 생겨 쓰지 않는다.
 */
val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF1C1C1E),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF636366),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFD1D1D6),
    error = Color(0xFFD70015),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFF3A3A3C),
    onSecondaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF48484A),
    error = Color(0xFFFF453A),
)

val GREEN = Color(0xFF34C759)

/**
 * 운전자마다 붙는 색. 계획 시간표, 주행 화면, 교대 화면, 도착 요약에서 같은 사람은 같은 색이라 이름을 읽지 않아도 누구 차례인지 보인다.
 * 흰 글자를 올리므로 밝은 주황·초록 대신 조금 짙은 색을 쓴다.
 */
val DRIVER_COLORS = listOf(Color(0xFF007AFF), Color(0xFFE06C00), Color(0xFF248A3D), Color(0xFF8E44D9))

/** 이름 첫 글자를 넣은 둥근 표시. */
@Composable
fun Avatar(name: String, color: Color, size: Dp = 52.dp, fontSize: Int = 22) {
    Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

/** 얼마나 찼는지 보여 주는 가로 막대. */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Box(modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.03f, 1f)).fillMaxHeight().background(color, CircleShape))
        }
    }
}

/** 지도 위에 뜨는 카드. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** 설정 앱처럼 묶음을 이루는 카드. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), RoundedCornerShape(24.dp), MaterialTheme.colorScheme.surface) {
        Column(Modifier.animateContentSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** iOS처럼 누르는 동안 살짝 작아진다. 손이 닿았다는 걸 눈으로 바로 알 수 있다. */
@Composable
private fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** 누른 것을 손끝으로도 느끼게 짧게 진동한다. 주요 동작은 조금 더 또렷하게. */
@Composable
private fun withHaptic(onClick: () -> Unit, strong: Boolean): () -> Unit {
    val view = LocalView.current
    return {
        view.performHapticFeedback(if (strong) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
        onClick()
    }
}

private const val PRESSED_SCALE = 0.96f

@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 80.dp, fontSize: Int = 24) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        withHaptic(onClick, strong = true),
        modifier.height(height).pressScale(interaction),
        enabled = enabled,
        shape = CircleShape,
        elevation = null,
        interactionSource = interaction,
    ) { Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 80.dp, fontSize: Int = 24) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        withHaptic(onClick, strong = false),
        modifier.height(height).pressScale(interaction),
        shape = CircleShape,
        elevation = null,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) { Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold) }
}

/** 둥근 유리 버튼. 뒤로 가기, 설정처럼 글자 없이 아이콘만 쓴다. */
@Composable
fun CircleButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, size: Dp = 64.dp) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        withHaptic(onClick, strong = false),
        modifier.size(size).pressScale(interaction),
        interactionSource = interaction,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), description, Modifier.size(size / 2), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.25f))
        }
    }
}

/** 가로로 조금 긴 아이콘 버튼. 그림자 없이 연한 바탕에 강조색 아이콘만 둔다. */
@Composable
fun IconPill(icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick,
        Modifier.width(150.dp).height(56.dp),
        enabled = enabled,
        shape = CircleShape,
        color = colors.secondaryContainer.copy(alpha = if (enabled) 1f else 0.5f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), description, Modifier.size(28.dp), tint = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.35f))
        }
    }
}

/** iOS의 세그먼트 컨트롤. 고른 칸만 떠 있는 알약이 된다. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, height: Dp = 64.dp) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(height).clip(CircleShape).background(colors.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val fill by animateColorAsState(if (on) colors.surface else colors.surface.copy(alpha = 0f), label = "segment")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .background(fill)
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), fontSize = 22.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** 눌러서 켜고 끄는 알약. 켜면 강조색으로 채워진다. 스위치보다 한 줄에 여러 개를 놓기 좋다. */
@Composable
fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: String? = null) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (on) colors.primary else colors.surfaceVariant, label = "chip")
    val interaction = remember { MutableInteractionSource() }
    Surface(
        withHaptic(onClick, strong = false),
        modifier.height(60.dp).pressScale(interaction),
        interactionSource = interaction,
        shape = CircleShape,
        color = fill,
        contentColor = if (on) colors.onPrimary else colors.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            leading?.let {
                Box(Modifier.size(30.dp).background(if (on) colors.onPrimary else colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (on) colors.primary else colors.onPrimary)
                }
            }
            Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 설정 앱의 켜고 끄는 줄. */
@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 22.sp)
        Switch(
            checked,
            { onChange() },
            colors = SwitchDefaults.colors(checkedTrackColor = GREEN, checkedThumbColor = Color.White, checkedBorderColor = GREEN),
        )
    }
}
