package com.octopas.sexy.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.capsule.ContinuousCapsule
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.ModelsViewModel
import com.octopas.sexy.data.LmModel
import com.octopas.sexy.data.LoadConfig
import com.octopas.sexy.ui.glass.AccentButton
import com.octopas.sexy.ui.glass.GlassIconButton
import com.octopas.sexy.ui.glass.LocalBackdrop
import com.octopas.sexy.ui.theme.IosType
import com.octopas.sexy.ui.theme.LocalIos

@Composable
fun ModelsScreen(app: AppViewModel, vm: ModelsViewModel, onUseInChat: (String) -> Unit) {
    val ios = LocalIos.current
    val backdrop = rememberLayerBackdrop()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<LmModel?>(null) }
    var dlModel by rememberSaveable { mutableStateOf("") }
    var dlQuant by rememberSaveable { mutableStateOf("") }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val loaded = vm.models.filter { it.isLoaded }
    val onDisk = vm.models.filter { !it.isLoaded }

    Box(Modifier.fillMaxSize().background(ios.groupedBackground)) {
        LazyColumn(
            Modifier.fillMaxSize().layerBackdrop(backdrop),
            contentPadding = PaddingValues(top = top + 60.dp, bottom = LocalTabBarSpace.current + 24.dp),
        ) {
            item {
                LargeTitle(
                    "Models",
                    subtitle = if (vm.isLmStudio) "${loaded.size} loaded · ${vm.models.size} on your PC · LM Studio"
                    else "${app.models.size} models · ${app.serverKind?.label ?: "server"}",
                )
            }
            if (!vm.isLmStudio) {
                item {
                    IosSection(footer = "Loading, ejecting and downloading models works with LM Studio. Other servers manage models themselves.") {
                        app.models.forEachIndexed { i, m ->
                            IosRow(m.id, subtitle = if (m.loaded) "loaded" else null, icon = Icons.Outlined.Memory, divider = i < app.models.lastIndex, onClick = { onUseInChat(m.id) })
                        }
                    }
                }
                return@LazyColumn
            }
            item { Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Segmented(listOf("My Models", "Download"), page, { page = it }) } }
            vm.error?.let { e -> item { IosSection { IosRow(e, titleColor = ios.red, divider = false, onClick = { vm.error = null }) } } }
            if (page == 0) {
                if (vm.loading && vm.models.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                if (loaded.isNotEmpty()) item {
                    IosSection("Loaded", footer = "Using your GPU's memory now.") {
                        loaded.forEachIndexed { i, m -> ModelRow(m, vm, app.model == m.key, i < loaded.lastIndex) { open = m } }
                    }
                }
                if (onDisk.isNotEmpty()) item {
                    IosSection("On your PC") {
                        onDisk.forEachIndexed { i, m -> ModelRow(m, vm, app.model == m.key, i < onDisk.lastIndex) { open = m } }
                    }
                }
            } else {
                item {
                    IosSection(
                        "Download a model",
                        footer = "Use an LM Studio catalog name (like qwen/qwen3-14b) or a Hugging Face link. The quantization (like Q4_K_M) only applies to Hugging Face links.",
                    ) {
                        IosTextRow(dlModel, { dlModel = it }, "qwen/qwen3-14b or https://huggingface.co/…")
                        IosTextRow(dlQuant, { dlQuant = it }, "Quantization (optional, e.g. Q4_K_M)", divider = false)
                    }
                    AccentButton(
                        "Download to PC", { vm.download(dlModel, dlQuant.ifBlank { null }); dlModel = "" },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), enabled = dlModel.isNotBlank(),
                    )
                }
                item {
                    IosSection("Suggested for an RTX 5080 (16 GB)", footer = "These fit on your GPU. Tap one to fill it in. If LM Studio doesn't know a name, paste the model's Hugging Face link instead.") {
                        val picks = listOf(
                            "google/gemma-4-26b-a4b" to "MoE · fast · images",
                            "openai/gpt-oss-20b" to "reasoning and tools",
                            "qwen/qwen3-14b" to "dense · reliable all-rounder",
                            "mistralai/mistral-small-3.2" to "dense 24B · solid",
                        )
                        picks.forEachIndexed { i, (k, d) -> IosRow(k, subtitle = d, icon = Icons.Outlined.CloudDownload, divider = i < picks.lastIndex, onClick = { dlModel = k }) }
                    }
                }
                if (vm.downloads.isNotEmpty()) item {
                    IosSection("Downloads") {
                        vm.downloads.forEachIndexed { i, d ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(d.model, style = IosType.body, color = ios.label, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(
                                        when {
                                            d.done -> "Done"
                                            d.status == "failed" -> "Failed"
                                            d.status == "paused" -> "Paused"
                                            else -> d.progress?.let { "${(it * 100).toInt()}%" } ?: "Starting…"
                                        },
                                        style = IosType.footnote, color = if (d.status == "failed") ios.red else ios.secondaryLabel,
                                    )
                                }
                                if (!d.done && d.status != "failed") {
                                    Spacer(Modifier.height(6.dp))
                                    if (d.progress != null) LinearProgressIndicator(progress = { d.progress!! }, modifier = Modifier.fillMaxWidth().clip(ContinuousCapsule))
                                    else LinearProgressIndicator(Modifier.fillMaxWidth().clip(ContinuousCapsule))
                                    Text(
                                        listOfNotNull(
                                            d.downloadedBytes?.let { formatBytes(it) + (d.totalBytes?.let { t -> " of " + formatBytes(t) } ?: "") },
                                            d.bytesPerSecond?.let { formatBytes(it.toLong()) + "/s" },
                                        ).joinToString(" · "),
                                        style = IosType.caption, color = ios.secondaryLabel, modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                            if (i < vm.downloads.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ios.separator))
                        }
                    }
                }
            }
        }
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.isLmStudio && loaded.isNotEmpty()) GlassIconButton(Icons.Outlined.Eject, "Eject all", { vm.unloadAll() })
                GlassIconButton(Icons.Outlined.Refresh, "Refresh", { vm.refresh(); app.checkStatus() })
            }
        }
    }

    open?.let { m -> ModelSheet(vm.models.firstOrNull { it.key == m.key } ?: m, vm, onUseInChat, onDismiss = { open = null }) }
}

@Composable
private fun ModelRow(m: LmModel, vm: ModelsViewModel, selected: Boolean, divider: Boolean, onClick: () -> Unit) {
    val ios = LocalIos.current
    IosRow(
        m.displayName,
        subtitle = listOfNotNull(m.params, m.quantization, formatBytes(m.sizeBytes), m.loaded.firstOrNull()?.contextLength?.let { "${it / 1024}K context" }).joinToString(" · "),
        icon = Icons.Outlined.Memory,
        iconColor = when { m.isLoaded -> ios.green; m.type == "embedding" -> ios.purple; else -> ios.secondaryLabel },
        divider = divider,
        chevron = true,
        onClick = onClick,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            vm.working[m.key]?.let { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
            if (selected) Badge("In use", ios.accent)
            if (m.vision) Badge("Vision", ios.orange)
            if (m.tools) Badge("Tools", ios.purple)
        }
    }
}

/** Like LM Studio's load panel. */
@Composable
private fun ModelSheet(m: LmModel, vm: ModelsViewModel, onUseInChat: (String) -> Unit, onDismiss: () -> Unit) {
    val ios = LocalIos.current
    var cfg by remember(m.key) { mutableStateOf(vm.loadConfigFor(m)) }
    var unloadOthers by remember { mutableStateOf(vm.unloadOthers) }
    val busy = vm.working[m.key]
    IosSheet(onDismiss, m.displayName) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (m.isLoaded) Badge("Loaded", ios.green)
            if (m.vision) Badge("Vision", ios.orange)
            if (m.tools) Badge("Tool use", ios.purple)
            if (m.reasoningOptions.isNotEmpty()) Badge("Reasoning", ios.accent)
            if (m.isMoe) Badge("MoE", ios.secondaryLabel)
        }
        IosSection("About") {
            IosRow("Model", value = m.key, divider = true)
            m.architecture?.let { IosRow("Architecture", value = it) }
            m.params?.let { IosRow("Parameters", value = it) }
            m.quantization?.let { IosRow("Quantization", value = it + (m.bitsPerWeight?.let { b -> " (%.1f bits)".format(b) } ?: "")) }
            IosRow("Size on disk", value = formatBytes(m.sizeBytes))
            m.maxContext?.let { IosRow("Max context", value = "${it / 1024}K tokens") }
            IosRow("Format", value = m.format ?: "?", divider = m.description != null)
            m.description?.let { Text(it, style = IosType.footnote, color = ios.secondaryLabel, modifier = Modifier.padding(16.dp)) }
        }
        IosSection(
            "Load settings",
            footer = "Smaller context = less GPU memory and faster. Flash Attention and the KV cache on the GPU are usually fastest. " +
                "If the model doesn't fit, LM Studio puts part of it in system RAM, which is much slower.",
        ) {
            val maxCtx = (m.maxContext ?: 131072).coerceAtLeast(4096)
            IosSliderRow("Context length", cfg.contextLength?.toFloat(), 2048f..maxCtx.toFloat(), { cfg = cfg.copy(contextLength = it?.let { v -> (v / 1024).toInt().coerceAtLeast(2) * 1024 }) }, { "${(it / 1024).toInt()}K" }, 8192f)
            IosToggleRow("Flash Attention", cfg.flashAttention ?: true, { cfg = cfg.copy(flashAttention = it) })
            IosToggleRow("KV cache on GPU", cfg.kvOnGpu ?: true, { cfg = cfg.copy(kvOnGpu = it) })
            if (m.isMoe) IosSliderRow("Experts per token", cfg.numExperts?.toFloat(), 1f..16f, { cfg = cfg.copy(numExperts = it?.toInt()) }, { it.toInt().toString() }, 8f)
            IosSliderRow("Batch size", cfg.evalBatchSize?.toFloat(), 64f..4096f, { cfg = cfg.copy(evalBatchSize = it?.let { v -> (v / 64).toInt() * 64 }) }, { it.toInt().toString() }, 512f, divider = true)
            IosToggleRow("Eject other models first", unloadOthers, { unloadOthers = it; vm.unloadOthers = it }, divider = false)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton(
                when { busy != null -> busy; m.isLoaded -> "Reload with these settings"; else -> "Load" },
                { vm.load(m, cfg) }, Modifier.fillMaxWidth(), enabled = busy == null && m.isChat,
            )
            if (m.isLoaded) AccentButton("Eject", { vm.unload(m) }, Modifier.fillMaxWidth(), enabled = busy == null, color = ios.red)
            if (m.isChat) AccentButton("Use in chat", { vm.saveLoadConfig(m, cfg); onUseInChat(m.key); onDismiss() }, Modifier.fillMaxWidth(), color = ios.green)
        }
    }
}

@Suppress("unused")
private fun LoadConfig.describe() = listOfNotNull(contextLength?.let { "${it / 1024}K" }, if (flashAttention == true) "FA" else null).joinToString(" · ")
