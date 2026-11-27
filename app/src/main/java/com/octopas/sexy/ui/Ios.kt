package com.octopas.sexy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.octopas.sexy.ui.glass.LiquidSlider
import com.octopas.sexy.ui.glass.LiquidToggle
import com.octopas.sexy.ui.theme.IosType
import com.octopas.sexy.ui.theme.LocalIos

/** Big bold title at the top of a page, like iOS "large titles". */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val ios = LocalIos.current
    Column(modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
        Text(title, style = IosType.largeTitle, color = ios.label)
        subtitle?.let { Text(it, style = IosType.footnote, color = ios.secondaryLabel) }
    }
}

/** Inset grouped list section (Settings-style), with an optional header and footer. */
@Composable
fun IosSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ios = LocalIos.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        header?.let {
            Text(it.uppercase(), style = IosType.footnote, color = ios.secondaryLabel, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        }
        Column(Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(26.dp)).background(ios.cell), content = content)
        footer?.let {
            Text(it, style = IosType.footnote, color = ios.secondaryLabel, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

/** Coloured rounded-square icon, like the ones in iOS Settings. */
@Composable
fun IosIcon(icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(30.dp).clip(ContinuousRoundedRectangle(8.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
    }
}

/** One row of a list: icon, title, optional subtitle, value and trailing control or chevron. */
@Composable
fun IosRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = LocalIos.current.accent,
    value: String? = null,
    titleColor: Color = LocalIos.current.label,
    chevron: Boolean = false,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ios = LocalIos.current
    Column(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IosIcon(icon, iconColor)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = IosType.body, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = IosType.footnote, color = ios.secondaryLabel, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            value?.let {
                Text(it, style = IosType.body, color = ios.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
            }
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
            if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = ios.tertiaryLabel, modifier = Modifier.padding(start = 4.dp))
        }
        if (divider) Box(Modifier.fillMaxWidth().padding(start = if (icon != null) 60.dp else 16.dp).height(0.5.dp).background(ios.separator))
    }
}

/** iOS switch, made of Liquid Glass. */
@Composable
fun IosToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, icon: ImageVector? = null, iconColor: Color = LocalIos.current.green, divider: Boolean = true) {
    IosRow(title, subtitle = subtitle, icon = icon, iconColor = iconColor, divider = divider, onClick = { onChange(!checked) }) {
        LiquidToggle(selected = { checked }, onSelect = onChange, backdrop = emptyBackdrop())
    }
}

/** A setting with a glass slider and its value; "Default" when not set. */
@Composable
fun IosSliderRow(
    title: String,
    value: Float?,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float?) -> Unit,
    format: (Float) -> String,
    defaultValue: Float = range.start,
    divider: Boolean = true,
) {
    val ios = LocalIos.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = IosType.body, color = ios.label, modifier = Modifier.weight(1f))
            Text(
                value?.let(format) ?: "Default",
                style = IosType.body,
                color = if (value == null) ios.secondaryLabel else ios.accent,
                modifier = Modifier.clip(ContinuousCapsule).clickable { if (value != null) onChange(null) }.padding(horizontal = 6.dp),
            )
        }
        LiquidSlider(
            value = { value ?: defaultValue },
            onValueChange = { onChange(it) },
            valueRange = range,
            visibilityThreshold = (range.endInclusive - range.start) / 1000f,
            backdrop = emptyBackdrop(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (divider) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ios.separator))
    }
}

/** Text field inside a list row. */
@Composable
fun IosTextRow(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    divider: Boolean = true,
) {
    val ios = LocalIos.current
    val style = (if (mono) IosType.mono.copy(fontSize = IosType.callout.fontSize) else IosType.body).copy(color = ios.label)
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = ios.tertiaryLabel))
            BasicTextField(
                value, onChange,
                textStyle = style,
                singleLine = singleLine,
                minLines = minLines,
                cursorBrush = SolidColor(ios.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (divider) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ios.separator))
    }
}

/** Search field under a large title. */
@Composable
fun IosSearchField(value: String, onChange: (String) -> Unit, placeholder: String = "Search", modifier: Modifier = Modifier) {
    val ios = LocalIos.current
    Row(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().height(40.dp).clip(ContinuousCapsule).background(ios.fill).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = ios.secondaryLabel, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = IosType.body, color = ios.secondaryLabel)
            BasicTextField(value, onChange, singleLine = true, textStyle = IosType.body.copy(color = ios.label), cursorBrush = SolidColor(ios.accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** iOS segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ios = LocalIos.current
    Row(
        modifier.fillMaxWidth().height(36.dp).clip(ContinuousCapsule).background(ios.fill).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(30.dp).clip(ContinuousCapsule)
                    .background(if (on) (if (ios.dark) Color(0xFF636366) else Color.White) else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = IosType.footnote.copy(fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else null), color = ios.label, maxLines = 1)
            }
        }
    }
}

/** Small rounded tag (model badges: "Vision", "Tools", "Loaded"...). */
@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, style = IosType.caption2, color = color,
        modifier = modifier.clip(ContinuousCapsule).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun SheetGrabber() {
    val ios = LocalIos.current
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(36.dp).height(5.dp).clip(ContinuousCapsule).background(ios.tertiaryLabel))
    }
}

val SmallText: TextStyle get() = IosType.footnote

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

/** Collapsible "thinking" text (used by the GitHub screen while the PC's AI works). */
@Composable
fun ThinkingBlock(reasoning: String, live: Boolean) {
    val ios = LocalIos.current
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(16.dp)).background(ios.fill)
            .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp), tint = ios.accent)
            Spacer(Modifier.width(8.dp))
            Text(if (live) "Thinking…" else "Thought process", style = IosType.subheadline, color = ios.label, modifier = Modifier.weight(1f))
            if (live) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        when {
            open -> Text(reasoning.trim(), style = IosType.footnote, color = ios.secondaryLabel, modifier = Modifier.padding(top = 8.dp))
            live -> Text(reasoning.trim().takeLast(220), style = IosType.footnote, color = ios.secondaryLabel, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
