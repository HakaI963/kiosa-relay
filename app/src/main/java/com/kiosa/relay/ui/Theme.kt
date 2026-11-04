package com.kiosa.relay.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---- Kiosa Relay palette — LIGHT ONLY ----
// white · near-black ink · light cyan · cyan grey
val Cyan = Color(0xFF22D3EE)
val LightCyan = Color(0xFFA5F3FC)
val CyanDeep = Color(0xFF0E7490)
val CyanInk = Color(0xFF155E75)
val CyanGrey = Color(0xFF52707A)
val InkBlack = Color(0xFF0A1417)
val MistBg = Color(0xFFF4FAFC)
val OkGreen = Color(0xFF0F9D77)
val WarnAmber = Color(0xFFD97706)
val ErrRed = Color(0xFFDC2626)

private val LightScheme = lightColorScheme(
    primary = CyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F3FA),
    onPrimaryContainer = CyanInk,
    secondary = CyanGrey,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EEF1),
    onSecondaryContainer = Color(0xFF22343A),
    tertiary = Cyan,
    background = MistBg,
    onBackground = InkBlack,
    surface = Color.White,
    onSurface = InkBlack,
    surfaceVariant = Color(0xFFEAF6F9),
    onSurfaceVariant = Color(0xFF3E5C64),
    outline = Color(0xFFC3D6DB),
    outlineVariant = Color(0xFFE2EDEF),
    error = ErrRed,
    onError = Color.White,
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val KiosaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun KiosaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        shapes = KiosaShapes,
        content = content,
    )
}
