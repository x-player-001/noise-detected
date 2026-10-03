package com.noisedetected.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 仪器风格的深色配色：石墨黑底、细线分层，琥珀色是唯一的强调色（与瀑布图的 magma 色带呼应）。
 * 只做深色：测量多在夜间进行，深色也让频谱和瀑布图更清楚。
 */
object Palette {
    val Bg = Color(0xFF0B0B0D)
    val Surface = Color(0xFF141417)
    val SurfaceHigh = Color(0xFF1C1C20)
    val Line = Color(0xFF26262B)
    val LineStrong = Color(0xFF3A3A41)

    val TextHigh = Color(0xFFF3F0EA)
    val TextMid = Color(0xFFA3A09A)
    val TextLow = Color(0xFF6B6A67)

    val Accent = Color(0xFFFFB547)
    val OnAccent = Color(0xFF1B1204)
    val AccentSoft = Color(0x1FFFB547)
    val Danger = Color(0xFFFF5F52)
    val DangerSoft = Color(0x1FFF5F52)
    val Positive = Color(0xFF7ED4A8)
}

/** 数字一律等宽（tnum），实时变化时不跳动。 */
private const val TABULAR = "tnum"

object Type {
    /** 主读数，如主频。 */
    val Hero = TextStyle(fontSize = 68.sp, lineHeight = 72.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, fontFeatureSettings = TABULAR)
    val Display = TextStyle(fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, fontFeatureSettings = TABULAR)
    val Unit = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp)
    val Title = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
    val Heading = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 24.sp)
    val BodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    /** 英文小标签：大写、加字距。 */
    val Micro = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp)
    val Label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
    val Number = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR)
    val NumberSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR, letterSpacing = 0.3.sp)
    val Button = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
    val Mono = FontFamily.Monospace
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Palette.Accent,
    secondary = Palette.TextMid,
    onSecondary = Palette.Bg,
    secondaryContainer = Palette.SurfaceHigh,
    onSecondaryContainer = Palette.TextHigh,
    tertiary = Palette.Accent,
    background = Palette.Bg,
    onBackground = Palette.TextHigh,
    surface = Palette.Bg,
    onSurface = Palette.TextHigh,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.TextMid,
    surfaceContainerLowest = Palette.Bg,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.LineStrong,
    outlineVariant = Palette.Line,
    error = Palette.Danger,
    onError = Palette.Bg,
    scrim = Color(0xCC000000),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
