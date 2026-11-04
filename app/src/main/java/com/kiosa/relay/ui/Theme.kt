package com.kiosa.relay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---- Kiosa Relay palette: white, black, light cyan, cyan grey ----
val Cyan = Color(0xFF22D3EE)          // accent
val LightCyan = Color(0xFFA5F3FC)     // highlights / dark-mode primary
val CyanDeep = Color(0xFF0E7490)      // primary on light (AA contrast on white)
val CyanGrey = Color(0xFF52707A)      // secondary text / dividers
val NightBg = Color(0xFF070B0D)       // near black
val NightSurface = Color(0xFF0D1417)
val OkGreen = Color(0xFF10B981)
val WarnAmber = Color(0xFFF59E0B)
val ErrRed = Color(0xFFEF4444)

private val LightScheme = lightColorScheme(
    primary = CyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF4FB),
    onPrimaryContainer = Color(0xFF062E36),
    secondary = CyanGrey,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2EDF0),
    onSecondaryContainer = Color(0xFF22343A),
    tertiary = Cyan,
    background = Color.White,
    onBackground = Color(0xFF0A1417),
    surface = Color.White,
    onSurface = Color(0xFF0A1417),
    surfaceVariant = Color(0xFFEAF6F9),
    onSurfaceVariant = Color(0xFF33525B),
    outline = Color(0xFFB9CDD3),
    outlineVariant = Color(0xFFDDE9EC),
    error = Color(0xFFDC2626),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = LightCyan,
    onPrimary = Color(0xFF062026),
    primaryContainer = Color(0xFF0B4A57),
    onPrimaryContainer = Color(0xFFC5F4FC),
    secondary = Color(0xFF93AEB6),
    onSecondary = Color(0xFF07222A),
    secondaryContainer = Color(0xFF22343A),
    onSecondaryContainer = Color(0xFFCBE2E8),
    tertiary = Cyan,
    background = NightBg,
    onBackground = Color(0xFFE7F5F8),
    surface = NightSurface,
    onSurface = Color(0xFFE7F5F8),
    surfaceVariant = Color(0xFF122024),
    onSurfaceVariant = Color(0xFFAFC9D1),
    outline = Color(0xFF2E454C),
    outlineVariant = Color(0xFF1B2C31),
    error = Color(0xFFF87171),
    onError = Color(0xFF2B0505),
)

@Composable
fun KiosaTheme(content: @Composable () -> Unit) {
    val mode = com.kiosa.relay.RelayController.settings.value.themeMode
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}
