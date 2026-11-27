package com.octopas.sexy.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.ChatViewModel
import com.octopas.sexy.ModelsViewModel
import com.octopas.sexy.data.Reasoning
import com.octopas.sexy.data.Sampling
import com.octopas.sexy.data.ServerKind
import com.octopas.sexy.ui.theme.IosType
import com.octopas.sexy.ui.theme.LocalIos

/** iOS-style sheet: rounded, grouped background, grabber. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosSheet(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
    val ios = LocalIos.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ios.groupedBackground,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        dragHandle = { SheetGrabber() },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(title, style = IosType.title3, color = ios.label, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            content()
        }
    }
}

/** Model menu in the chat title: every model on the PC, loaded ones first. */
@Composable
fun ModelPickerSheet(app: AppViewModel, models: ModelsViewModel, onDismiss: () -> Unit) {
    val ios = LocalIos.current
    IosSheet(onDismiss, "Model") {
        if (models.isLmStudio && models.models.isNotEmpty()) {
            val chatModels = models.models.filter { it.isChat }
            IosSection(
                footer = "Models that aren't loaded load when you send a message (with the settings from the Models tab).",
            ) {
                chatModels.forEachIndexed { i, m ->
                    IosRow(
                        m.displayName,
                        subtitle = listOfNotNull(m.params, m.quantization, formatBytes(m.sizeBytes), if (m.isLoaded) "loaded" else null).joinToString(" · "),
                        icon = Icons.Outlined.Memory,
                        iconColor = if (m.isLoaded) ios.green else ios.secondaryLabel,
                        divider = i < chatModels.lastIndex,
                        onClick = { app.selectModel(m.key); onDismiss() },
                    ) {
                        when {
                            models.working.containsKey(m.key) -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            app.model == m.key -> Icon(Icons.Filled.CheckCircle, "Selected", tint = ios.accent)
                        }
                    }
                }
            }
        } else {
            IosSection(footer = if (app.serverKind != ServerKind.LM_STUDIO) "Connected to ${app.serverKind?.label ?: "a server"}." else null) {
                app.models.forEachIndexed { i, m ->
                    IosRow(
                        m.id, subtitle = if (m.loaded) "loaded" else null, divider = i < app.models.lastIndex,
                        onClick = { app.selectModel(m.id); onDismiss() },
                    ) { if (app.model == m.id) Icon(Icons.Filled.CheckCircle, "Selected", tint = ios.accent) }
                }
                if (app.models.isEmpty()) IosRow("No models found", subtitle = "Download one in the Models tab.", divider = false)
            }
        }
    }
}

/** Everything LM Studio's right-hand panel has for one chat. */
@Composable
fun ChatSettingsSheet(chat: ChatViewModel, models: ModelsViewModel, app: AppViewModel, onDismiss: () -> Unit) {
    val ios = LocalIos.current
    val context = LocalContext.current
    var name by remember { mutableStateOf(chat.doc.name.orEmpty()) }
    var system by remember { mutableStateOf(chat.doc.systemPrompt) }
    val s = chat.sampling
    val model = models.models.firstOrNull { it.key == app.model }
    IosSheet({ chat.rename(name); chat.setSystemPrompt(system); onDismiss() }, "Chat settings") {
        IosSection("Name") { IosTextRow(name, { name = it }, chat.doc.title, divider = false) }
        IosSection("System prompt", footer = "Instructions the model follows in this chat. Saved in LM Studio too.") {
            IosTextRow(system, { system = it }, "You are a helpful assistant…", singleLine = false, minLines = 3, divider = false)
        }
        IosSection("Thinking", footer = model?.reasoningOptions?.takeIf { it.isNotEmpty() }?.let { "This model supports: ${it.joinToString()}" }) {
            Box(Modifier.padding(12.dp)) {
                val options = Reasoning.entries
                Segmented(options.map { it.label }, options.indexOf(s.reasoning), { chat.updateSampling(s.copy(reasoning = options[it])) })
            }
        }
        SamplingSection(s) { chat.updateSampling(it) }
        if (app.serverKind == ServerKind.LM_STUDIO) {
            IosSection(
                "Plugins & tools",
                footer = if (models.plugins.isEmpty()) "No plugins found. They come from LM Studio on the PC (needs the SEXY bridge)."
                else "Tools run on your PC through LM Studio. Turn on \"Allow calling servers from mcp.json\" in LM Studio's server settings.",
            ) {
                models.plugins.forEachIndexed { i, p ->
                    IosToggleRow(
                        p.name, p.id in chat.doc.plugins,
                        { on -> chat.setPlugins(if (on) chat.doc.plugins + p.id else chat.doc.plugins - p.id) },
                        subtitle = p.id, icon = Icons.Outlined.Extension, iconColor = ios.purple, divider = i < models.plugins.lastIndex,
                    )
                }
            }
        }
        IosSection {
            IosToggleRow("Pinned", chat.doc.pinned, { chat.togglePin() }, icon = Icons.Outlined.PushPin, iconColor = ios.orange)
            IosRow("Share chat", icon = Icons.Outlined.Share, onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, chat.transcript()), null))
            })
            IosRow("Delete chat", icon = Icons.Outlined.Delete, iconColor = ios.red, titleColor = ios.red, divider = false, onClick = {
                chat.deleteChat(chat.doc.id); onDismiss()
            })
        }
    }
}

/** Temperature, top-p, top-k, min-p, repeat penalty, max tokens, context, seed. Tap a value to reset it. */
@Composable
fun SamplingSection(s: Sampling, header: String = "Sampling", onChange: (Sampling) -> Unit) {
    IosSection(header, footer = "\"Default\" uses the model's own setting. Tap a value to reset it.") {
        IosSliderRow("Temperature", s.temperature?.toFloat(), 0f..2f, { onChange(s.copy(temperature = it?.toDouble()?.round2())) }, { "%.2f".format(it) }, 0.8f)
        IosSliderRow("Top P", s.topP?.toFloat(), 0f..1f, { onChange(s.copy(topP = it?.toDouble()?.round2())) }, { "%.2f".format(it) }, 0.95f)
        IosSliderRow("Top K", s.topK?.toFloat(), 1f..200f, { onChange(s.copy(topK = it?.toInt())) }, { it.toInt().toString() }, 40f)
        IosSliderRow("Min P", s.minP?.toFloat(), 0f..1f, { onChange(s.copy(minP = it?.toDouble()?.round2())) }, { "%.2f".format(it) }, 0.05f)
        IosSliderRow("Repeat penalty", s.repeatPenalty?.toFloat(), 1f..2f, { onChange(s.copy(repeatPenalty = it?.toDouble()?.round2())) }, { "%.2f".format(it) }, 1.1f)
        IosSliderRow("Max answer length", s.maxTokens?.toFloat(), 256f..32768f, { onChange(s.copy(maxTokens = it?.let { v -> (v / 256).toInt() * 256 })) }, { "${it.toInt()} tokens" }, 4096f)
        IosSliderRow("Context (tools chats)", s.contextLength?.toFloat(), 2048f..131072f, { onChange(s.copy(contextLength = it?.let { v -> (v / 1024).toInt() * 1024 })) }, { "${(it / 1024).toInt()}K" }, 16384f)
        IosSliderRow("Seed", s.seed?.toFloat(), 0f..9999f, { onChange(s.copy(seed = it?.toInt())) }, { it.toInt().toString() }, 42f, divider = false)
    }
}

private fun Double.round2() = kotlin.math.round(this * 100) / 100
