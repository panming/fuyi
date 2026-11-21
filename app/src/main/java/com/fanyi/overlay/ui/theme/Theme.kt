package com.fanyi.overlay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF12151C)
val InkRaised = Color(0xFF1C212B)
val Amber = Color(0xFFE6A23C)
val Celadon = Color(0xFF9DCFC2)
val Mist = Color(0xFFE7E2D8)
val Danger = Color(0xFFE36B6B)

private val Colors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1C06),
    primaryContainer = Color(0xFF4A3412),
    onPrimaryContainer = Color(0xFFFFE3B8),
    secondary = Celadon,
    onSecondary = Color(0xFF08332C),
    secondaryContainer = Color(0xFF1C3F38),
    onSecondaryContainer = Color(0xFFD5F3EB),
    background = Ink,
    onBackground = Mist,
    surface = Ink,
    onSurface = Mist,
    surfaceVariant = InkRaised,
    onSurfaceVariant = Color(0xFFC9C2B6),
    error = Danger,
    onError = Color(0xFF2A0C0C),
    outline = Color(0xFF3A4150),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

private val AppShapes = Shapes(
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun FanyiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = Type,
        shapes = AppShapes,
        content = content,
    )
}
