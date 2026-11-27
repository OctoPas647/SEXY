package com.octopas.sexy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.ChatViewModel
import com.octopas.sexy.ModelsViewModel
import com.octopas.sexy.SexyApp
import com.octopas.sexy.ServerStatus
import com.octopas.sexy.data.SyncState
import com.octopas.sexy.ui.theme.LocalIos
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: AppViewModel, chat: ChatViewModel, models: ModelsViewModel) {
    val ios = LocalIos.current
    val context = LocalContext.current
    val sexy = context.applicationContext as SexyApp
    val settings = app.settings
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val sync by chat.syncState.collectAsState()

    var keepCopy by remember { mutableStateOf(settings.keepLocalCopy) }
    var confirmCopyOff by remember { mutableStateOf(false) }
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt) }
    var defaultSampling by remember { mutableStateOf(settings.defaultSampling) }
    var defaultPlugins by remember { mutableStateOf(settings.defaultPlugins) }
    var thinking by remember { mutableStateOf(settings.thinking) }
    var bridgeUrl by remember { mutableStateOf(settings.bridgeUrl.orEmpty()) }
    var note by remember { mutableStateOf<String?>(null) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    LaunchedEffect(Unit) { app.checkBridge(); models.refreshPlugins() }

    Box(Modifier.fillMaxSize().background(ios.groupedBackground)) {
        LazyColumn(
            Modifier.fillMaxSize().layerBackdrop(backdrop),
            contentPadding = PaddingValues(top = top + 16.dp, bottom = LocalTabBarSpace.current + 24.dp),
        ) {
            item { LargeTitle("Settings") }
            item {
                IosSection("Your PC", footer = "SEXY finds your PC by itself whenever Tailscale is on — LM Studio, Ollama, llama.cpp, vLLM, Jan, KoboldCpp, text-generation-webui, GPT4All or Open WebUI.") {
                    IosRow(
                        app.serverKind?.label ?: "AI server",
                        subtitle = app.serverUrl,
                        icon = Icons.Outlined.Computer,
                        iconColor = if (app.status == ServerStatus.Online) ios.green else ios.red,
                        value = when (app.status) {
                            ServerStatus.Online -> "Online"
                            ServerStatus.Checking -> "Checking…"
                            ServerStatus.NoTailscale -> "Tailscale off"
                            is ServerStatus.Offline -> "Offline"
                        },
                    )
                    IosRow(
                        "Chat bridge",
                        subtitle = if (app.bridgeOnline) "Chats, uploads and the terminal work." else "Not running — run SEXY-LMSTUDIO.bat in D:\\AI\\LMStudio once.",
                        icon = Icons.Outlined.Link, iconColor = if (app.bridgeOnline) ios.green else ios.orange,
                        value = if (app.bridgeOnline) "Connected" else "Offline",
                        onClick = { app.checkBridge() },
                    )
                    IosRow("Search again", icon = Icons.Outlined.Search, onClick = { app.findServer() })
                    IosRow("Use another PC", icon = Icons.Outlined.SwapHoriz, iconColor = ios.secondaryLabel, divider = false, onClick = { chat.stop(); app.disconnect() })
                }
            }
            item {
                IosSection(
                    "Chats",
                    footer = "Chats are stored on your PC in LM Studio's own chat folder, so you see the same chats here and in LM Studio. " +
                        "With the copy on, you can also read them when the PC is off; new chats are sent to the PC when it's back.",
                ) {
                    IosToggleRow(
                        "Keep a copy on this phone", keepCopy,
                        { on ->
                            if (on) { keepCopy = true; settings.keepLocalCopy = true; scope.launch { note = "Copied ${sexy.chats.downloadAll()} chats to this phone." } }
                            else confirmCopyOff = true
                        },
                        icon = Icons.Outlined.PhoneAndroid, iconColor = ios.accent,
                    )
                    IosRow(
                        "Sync now",
                        subtitle = when (sync) {
                            SyncState.SYNCED -> "Everything is on your PC."
                            SyncState.PENDING, SyncState.SYNCING -> "${sexy.chats.pendingCount} chat(s) waiting for the PC."
                            SyncState.PC_OFFLINE -> "Your PC can't be reached."
                            SyncState.NO_BRIDGE -> "The bridge isn't running on the PC."
                            SyncState.ON_PHONE -> "This server isn't LM Studio, so chats are saved on this phone."
                        },
                        icon = Icons.Outlined.CloudSync, iconColor = ios.green, divider = keepCopy,
                        onClick = { chat.syncNow(); note = "Syncing with your PC…" },
                    )
                    if (keepCopy) IosRow("Download all chats to this phone", icon = Icons.Outlined.Download, divider = false, onClick = {
                        scope.launch { note = "Copied ${sexy.chats.downloadAll()} chats to this phone." }
                    })
                }
                note?.let { Text(it, style = SmallText, color = ios.secondaryLabel, modifier = Modifier.padding(horizontal = 32.dp)) }
            }
            item {
                IosSection("New chats", footer = "Each chat can change these in its ⋯ menu.") {
                    IosTextRow(systemPrompt, { systemPrompt = it; settings.systemPrompt = it }, "System prompt for new chats", singleLine = false, minLines = 2)
                    IosToggleRow("Think before answering", thinking, { thinking = it; settings.thinking = it; chat.updateThinking(it) }, icon = Icons.Outlined.Psychology, iconColor = ios.purple, divider = false)
                }
            }
            if (models.plugins.isNotEmpty()) item {
                IosSection("Plugins for new chats", footer = "LM Studio plugins and MCP servers from your PC. They run on the PC.") {
                    models.plugins.forEachIndexed { i, p ->
                        IosToggleRow(
                            p.name, p.id in defaultPlugins,
                            { on -> defaultPlugins = if (on) defaultPlugins + p.id else defaultPlugins - p.id; settings.defaultPlugins = defaultPlugins },
                            subtitle = p.id, icon = Icons.Outlined.Extension, iconColor = ios.purple, divider = i < models.plugins.lastIndex,
                        )
                    }
                }
            }
            item { SamplingSection(defaultSampling, "Default sampling") { defaultSampling = it; settings.defaultSampling = it } }
            item {
                IosSection("Advanced", footer = "Leave empty to use port 1235 on the same PC as the AI server.") {
                    IosTextRow(bridgeUrl, { bridgeUrl = it; settings.bridgeUrl = it.trim().ifBlank { null } }, "Bridge address (automatic)", divider = false)
                }
            }
            item {
                IosSection("About", footer = "Liquid Glass by Kyant0/AndroidLiquidGlass (Apache 2.0). Inter font by Rasmus Andersson (SIL OFL). PDF reading by PdfBox-Android (Apache 2.0).") {
                    IosRow("SEXY", value = version, icon = Icons.Outlined.Info, iconColor = ios.secondaryLabel, divider = false)
                }
            }
        }
    }

    if (confirmCopyOff) AlertDialog(
        onDismissRequest = { confirmCopyOff = false },
        title = { Text("Remove the copy from this phone?") },
        text = {
            Text(
                if (sexy.chats.pendingCount > 0) "${sexy.chats.pendingCount} chat(s) haven't reached your PC yet and would be lost. Sync first."
                else "Your chats stay on your PC. This phone will only show them while it can reach the PC.",
            )
        },
        confirmButton = {
            TextButton({
                keepCopy = false; settings.keepLocalCopy = false; sexy.chats.deleteLocalCopy(); confirmCopyOff = false; chat.refreshList()
            }, enabled = sexy.chats.pendingCount == 0) { Text("Remove", color = ios.red) }
        },
        dismissButton = { TextButton({ confirmCopyOff = false }) { Text("Keep") } },
    )
}
