package com.octopas.sexy.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.octopas.sexy.ui.theme.LocalIos

/**
 * What the glass on the current screen sees through: the screen's scrolling content.
 * Screens record their content with Modifier.layerBackdrop(...) and provide it here for their bars and buttons.
 */
val LocalBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/** Liquid Glass material: frosted, vibrant, with a refracting lens edge (iOS 26). */
@Composable
fun Modifier.glass(
    shape: Shape = ContinuousCapsule,
    blurRadius: Dp = 10.dp,
    lens: Boolean = true,
    tint: Color = Color.Unspecified,
    backdrop: Backdrop = LocalBackdrop.current,
): Modifier {
    val ios = LocalIos.current
    val surface = if (tint.isSpecified) tint else ios.glassTint
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            if (lens) lens(12.dp.toPx(), 20.dp.toPx())
        },
        onDrawSurface = { drawRect(surface) },
    )
}

/** Round glass button (back, menu, new chat...), 44 pt like iOS. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalIos.current.label,
    accent: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    val ios = LocalIos.current
    Box(
        modifier
            .size(size)
            .glass(ContinuousCapsule, tint = if (accent) ios.accent else Color.Unspecified)
            .clip(ContinuousCapsule)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription, Modifier.size(size * 0.48f),
            tint = if (accent) Color.White else if (enabled) tint else ios.tertiaryLabel,
        )
    }
}

/** Glass capsule with any content (the model picker in the chat title, toolbar groups...). */
@Composable
fun GlassPill(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .height(height)
            .glass(ContinuousCapsule)
            .clip(ContinuousCapsule)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Solid accent capsule button (Load, Download, Send...). */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = LocalIos.current.accent,
) {
    val ios = LocalIos.current
    Box(
        modifier
            .height(50.dp)
            .clip(ContinuousRoundedRectangle(16.dp))
            .background(if (enabled) color else ios.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text,
            style = com.octopas.sexy.ui.theme.IosType.headline,
            color = if (enabled) Color.White else ios.tertiaryLabel,
        )
    }
}
