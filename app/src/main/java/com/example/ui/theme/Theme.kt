package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = Color(0xFF002028),
    primaryContainer = Color(0xFF004D59),
    onPrimaryContainer = Color(0xFFB5EFFF),
    secondary = ElectricTeal,
    onSecondary = Color(0xFF001F28),
    background = DarkNavyBg,
    onBackground = TextLight,
    surface = SurfaceCard,
    onSurface = TextLight,
    surfaceVariant = SurfaceCardLight,
    onSurfaceVariant = TextMuted,
    error = StatusRed,
    onError = Color.White
)

private val LightColorScheme = darkColorScheme(
    primary = CyberCyan,
    background = DarkNavyBg,
    surface = SurfaceCard,
    onSurface = TextLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep consistent cyber theme for framework app
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
