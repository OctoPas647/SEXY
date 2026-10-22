package com.octopas.sexy.ui.theme

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

val SexyPink = Color(0xFFFF3D7F)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFFFB1C5),
    onPrimary = Color(0xFF65002B),
    primaryContainer = Color(0xFF8E1F48),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFFE3BDC6),
    background = Color(0xFF15121A),
    surface = Color(0xFF15121A),
)

private val LightFallback = lightColorScheme(
    primary = Color(0xFFB0285A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E1),
    onPrimaryContainer = Color(0xFF3F0019),
)

/** Material You: uses your wallpaper colors on Android 12+, pink fallback otherwise. */
@Composable
fun SexyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
