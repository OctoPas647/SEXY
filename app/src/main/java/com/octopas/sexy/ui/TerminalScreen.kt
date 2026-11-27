package com.octopas.sexy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.TerminalViewModel
import com.octopas.sexy.ui.glass.GlassIconButton
import com.octopas.sexy.ui.glass.GlassPill
import com.octopas.sexy.ui.glass.LocalBackdrop
import com.octopas.sexy.ui.glass.glass
import com.octopas.sexy.ui.theme.IosType
import com.octopas.sexy.ui.theme.LocalIos

private val TermBg = Color(0xFF0B0B0D)
private val TermText = Color(0xFFE6E6E6)
private val TermPrompt = Color(0xFF5AC8FA)
private val TermCmd = Color(0xFFFFFFFF)
private val TermErr = Color(0xFFFF6961)

/** PowerShell on the PC, through the SEXY bridge (Tailscale only). Always dark, like a terminal. */
@Composable
fun TerminalScreen(app: AppViewModel, vm: TerminalViewModel) {
    CompositionLocalProvider(LocalIos provides com.octopas.sexy.ui.theme.DarkIos) { TerminalBody(app, vm) }
}

@Composable
private fun TerminalBody(app: AppViewModel, vm: TerminalViewModel) {
    val ios = LocalIos.current
    val backdrop = rememberLayerBackdrop()
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LaunchedEffect(vm.entries.size, vm.entries.lastOrNull()?.output?.length) {
        if (vm.entries.isNotEmpty()) list.animateScrollToItem(vm.entries.lastIndex + 1)
    }

    Box(Modifier.fillMaxSize().background(TermBg)) {
        LazyColumn(
            Modifier.fillMaxSize().layerBackdrop(backdrop),
            state = list,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top + 64.dp, bottom = LocalTabBarSpace.current + 130.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Windows PowerShell on ${app.serverUrl.substringAfter("://").substringBefore(':')}\n" +
                        "Commands run on your PC as you. Each runs in its own PowerShell (cd is remembered). " +
                        "Long-running programs are stopped after ${vm.timeoutSec}s.",
                    style = IosType.mono, color = TermText.copy(alpha = 0.55f),
                )
            }
            itemsIndexed(vm.entries) { _, e ->
                SelectionContainer {
                    Column {
                        Row {
                            Text("PS ${e.cwd}> ", style = IosType.mono, color = TermPrompt)
                            Text(e.command, style = IosType.mono, color = TermCmd)
                        }
                        when {
                            e.running -> CircularProgressIndicator(Modifier.padding(top = 6.dp).size(14.dp), strokeWidth = 2.dp, color = TermPrompt)
                            !e.output.isNullOrEmpty() -> Box(Modifier.horizontalScroll(rememberScrollState())) {
                                Text(e.output, style = IosType.mono, color = if ((e.exitCode ?: 0) != 0) TermErr else TermText, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassPill(onClick = null, modifier = Modifier.weight(1f)) {
                    Box(Modifier.size(8.dp).clip(ContinuousCapsule).background(if (app.bridgeOnline) ios.green else ios.red))
                    Text(
                        vm.cwd.ifBlank { "Terminal" }, style = IosType.subheadline, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(10.dp))
                GlassIconButton(Icons.Outlined.DeleteSweep, "Clear", { vm.clear() }, tint = Color.White)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().padding(bottom = LocalTabBarSpace.current).navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val quick = listOf("nvidia-smi", "lms ps", "lms ls", "Get-Process LM* | Select Name,CPU,WS", "tailscale status", "dir", "cd ..")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quick) { q ->
                        Text(
                            q, style = IosType.mono, color = Color.White,
                            modifier = Modifier.glass(ContinuousCapsule).clip(ContinuousCapsule).clickable { vm.run(q) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    GlassIconButton(Icons.Outlined.KeyboardArrowUp, "Previous command", { vm.previous()?.let { input = it } }, tint = Color.White, size = 44.dp)
                    Spacer(Modifier.size(6.dp))
                    GlassIconButton(Icons.Outlined.KeyboardArrowDown, "Next command", { input = vm.next() }, tint = Color.White, size = 44.dp)
                    Spacer(Modifier.size(8.dp))
                    Row(
                        Modifier.weight(1f).heightIn(min = 48.dp).glass(ContinuousRoundedRectangle(24.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (input.isEmpty()) Text("Command", style = IosType.mono, color = Color.White.copy(alpha = 0.4f))
                            BasicTextField(
                                input, { input = it },
                                textStyle = IosType.mono.copy(color = Color.White),
                                cursorBrush = SolidColor(TermPrompt),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { vm.run(input); input = "" }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(
                            Modifier.size(40.dp).clip(ContinuousCapsule).background(if (vm.running || input.isNotBlank()) ios.accent else Color.White.copy(alpha = 0.12f))
                                .clickable { if (vm.running) vm.cancel() else { vm.run(input); input = "" } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (vm.running) Icons.Filled.Stop else Icons.Filled.ArrowUpward, if (vm.running) "Stop" else "Run", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
