package com.octopas.sexy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.octopas.sexy.R

/** Apple's system colours (UIKit), light and dark. */
@Immutable
data class IosColors(
    val dark: Boolean,
    val accent: Color,
    val background: Color,          // systemBackground
    val groupedBackground: Color,   // systemGroupedBackground
    val cell: Color,                // secondarySystemGroupedBackground
    val fill: Color,                // tertiarySystemFill
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val red: Color,
    val green: Color,
    val orange: Color,
    val purple: Color,
    val userBubble: Color,
    val glassTint: Color,
)

val LightIos = IosColors(
    dark = false,
    accent = Color(0xFF007AFF),
    background = Color(0xFFFFFFFF),
    groupedBackground = Color(0xFFF2F2F7),
    cell = Color(0xFFFFFFFF),
    fill = Color(0x1F767680),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    red = Color(0xFFFF3B30),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    purple = Color(0xFFAF52DE),
    userBubble = Color(0xFF007AFF),
    glassTint = Color(0xFFFAFAFA).copy(alpha = 0.55f),
)

val DarkIos = IosColors(
    dark = true,
    accent = Color(0xFF0A84FF),
    background = Color(0xFF000000),
    groupedBackground = Color(0xFF000000),
    cell = Color(0xFF1C1C1E),
    fill = Color(0x3D767680),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    purple = Color(0xFFBF5AF2),
    userBubble = Color(0xFF0A84FF),
    glassTint = Color(0xFF1C1C1E).copy(alpha = 0.5f),
)

val LocalIos = staticCompositionLocalOf { DarkIos }

/** Inter: the closest open font to Apple's SF Pro. Display cut for big titles, like SF Pro Display. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
val InterDisplay = FontFamily(Font(R.font.inter_display_bold, FontWeight.Bold))

/** Apple's text styles (Dynamic Type, "Large" size). */
object IosType {
    val largeTitle = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.01.em)
    val title1 = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp)
    val title2 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em)
    val title3 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.01).em)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.02).em)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = (-0.02).em)
    val callout = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.02).em)
    val subheadline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.015).em)
    val footnote = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.005).em)
    val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.006.em)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
fun SexyTheme(content: @Composable () -> Unit) {
    val ios = if (isSystemInDarkTheme()) DarkIos else LightIos
    // Material components (dialogs, menus, the GitHub screen) get the same Apple palette and font.
    val scheme = if (ios.dark) darkColorScheme(
        primary = ios.accent, onPrimary = Color.White, primaryContainer = ios.accent.copy(alpha = 0.25f), onPrimaryContainer = ios.label,
        secondary = ios.accent, secondaryContainer = ios.fill, onSecondaryContainer = ios.label,
        background = ios.groupedBackground, onBackground = ios.label, surface = ios.groupedBackground, onSurface = ios.label,
        surfaceVariant = ios.cell, onSurfaceVariant = ios.secondaryLabel, surfaceContainer = ios.cell,
        surfaceContainerLow = ios.cell, surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainerHighest = Color(0xFF3A3A3C),
        outline = ios.separator, outlineVariant = ios.separator, error = ios.red, tertiary = ios.purple,
    ) else lightColorScheme(
        primary = ios.accent, onPrimary = Color.White, primaryContainer = ios.accent.copy(alpha = 0.15f), onPrimaryContainer = ios.label,
        secondary = ios.accent, secondaryContainer = ios.fill, onSecondaryContainer = ios.label,
        background = ios.groupedBackground, onBackground = ios.label, surface = ios.groupedBackground, onSurface = ios.label,
        surfaceVariant = ios.cell, onSurfaceVariant = ios.secondaryLabel, surfaceContainer = ios.cell,
        surfaceContainerLow = ios.cell, surfaceContainerHigh = Color(0xFFE9E9EE), surfaceContainerHighest = Color(0xFFE3E3E8),
        outline = ios.separator, outlineVariant = ios.separator, error = ios.red, tertiary = ios.purple,
    )
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = InterDisplay),
        displayMedium = base.displayMedium.copy(fontFamily = InterDisplay),
        displaySmall = base.displaySmall.copy(fontFamily = InterDisplay),
        headlineLarge = IosType.largeTitle,
        headlineMedium = IosType.title1,
        headlineSmall = IosType.title2,
        titleLarge = IosType.title3,
        titleMedium = IosType.headline,
        titleSmall = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = IosType.body,
        bodyMedium = IosType.subheadline,
        bodySmall = IosType.footnote,
        labelLarge = IosType.callout.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = IosType.footnote.copy(fontWeight = FontWeight.Medium),
        labelSmall = IosType.caption2,
    )
    CompositionLocalProvider(LocalIos provides ios) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
