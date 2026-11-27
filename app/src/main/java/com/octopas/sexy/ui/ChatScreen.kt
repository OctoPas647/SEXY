package com.octopas.sexy.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.ChatViewModel
import com.octopas.sexy.ModelsViewModel
import com.octopas.sexy.ServerStatus
import com.octopas.sexy.data.ChatMessage
import com.octopas.sexy.data.MsgVersion
import com.octopas.sexy.data.ServerKind
import com.octopas.sexy.ui.glass.GlassIconButton
import com.octopas.sexy.ui.glass.GlassPill
import com.octopas.sexy.ui.glass.LocalBackdrop
import com.octopas.sexy.ui.glass.glass
import com.octopas.sexy.ui.theme.IosType
import com.octopas.sexy.ui.theme.LocalIos
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(app: AppViewModel, chat: ChatViewModel, models: ModelsViewModel) {
    val ios = LocalIos.current
    val backdrop = rememberLayerBackdrop()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scope = rememberCoroutineScope()

    androidx.activity.compose.BackHandler { chat.close() }

    // Follow the answer while it streams, like iMessage.
    val last = chat.messages.lastOrNull()?.current
    LaunchedEffect(chat.messages.size, last?.text?.length, last?.reasoning?.length) {
        if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.lastIndex + 1)
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(chat::attach) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris -> uris.forEach(chat::attach) }

    CompositionLocalProvider(LocalContentColor provides ios.label) {
        Box(Modifier.fillMaxSize().background(ios.background)) {
            LazyColumn(
                Modifier.fillMaxSize().layerBackdrop(backdrop),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 72.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (chat.messages.isEmpty() && !chat.opening) item { EmptyChat(app) }
                if (chat.opening) item {
                    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                itemsIndexed(chat.messages, key = { i, m -> "$i-${m.versions.size}-${m.selected}" }) { i, m ->
                    val streaming = chat.busy && i == chat.messages.lastIndex && m.role == "assistant"
                    if (m.role == "user") UserMessage(m, i, chat, onEdit = { editing = i })
                    else AssistantMessage(m, i, chat, streaming)
                }
                item { Spacer(Modifier.size(1.dp)) }
            }

            CompositionLocalProvider(LocalBackdrop provides backdrop) {
                // Top bar: back, model picker, chat settings (floating glass, iOS 26).
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Chats", { chat.close() })
                    Spacer(Modifier.weight(1f))
                    GlassPill(onClick = { picker = true; models.refresh() }, modifier = Modifier.widthIn(max = 240.dp)) {
                        val online = app.status == ServerStatus.Online
                        Box(Modifier.size(8.dp).clip(ContinuousCapsule).background(if (online) ios.green else ios.red))
                        Column {
                            Text(
                                app.model.ifBlank { "Choose a model" }.substringAfterLast('/'),
                                style = IosType.subheadline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = ios.label,
                            )
                            Text(chat.doc.title, style = IosType.caption2, color = ios.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ios.secondaryLabel)
                    }
                    Spacer(Modifier.weight(1f))
                    GlassIconButton(Icons.Outlined.MoreHoriz, "Chat settings", { settingsOpen = true; models.refreshPlugins() })
                }

                // Bottom: status, attachments, input.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chat.errorBanner?.let { msg ->
                        Row(
                            Modifier.fillMaxWidth().glass(ContinuousRoundedRectangle(18.dp), tint = ios.red.copy(alpha = 0.18f)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(msg, style = IosType.footnote, color = ios.label, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.Close, "Dismiss", Modifier.size(20.dp).clickable { chat.errorBanner = null }, tint = ios.secondaryLabel)
                        }
                    }
                    chat.phase?.let { p ->
                        Row(Modifier.glass(ContinuousCapsule).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ios.accent)
                            Spacer(Modifier.size(8.dp))
                            Text(p, style = IosType.footnote, color = ios.label)
                        }
                    }
                    if (chat.doc.plugins.isNotEmpty() && app.serverKind == ServerKind.LM_STUDIO) {
                        Text(
                            "Tools on: " + chat.doc.plugins.joinToString { it.substringAfter('/') },
                            style = IosType.caption2, color = ios.secondaryLabel, modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    if (chat.pending.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(chat.pending) { p ->
                                Row(Modifier.glass(ContinuousCapsule).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (p.uploading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    else Icon(if (p.attachment.isImage) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, null, Modifier.size(16.dp), tint = if (p.error != null) ios.orange else ios.accent)
                                    Spacer(Modifier.size(6.dp))
                                    Text(p.attachment.name, style = IosType.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                                    Icon(Icons.Outlined.Close, "Remove", Modifier.padding(start = 4.dp).size(18.dp).clickable { chat.removeAttachment(p) }, tint = ios.secondaryLabel)
                                }
                            }
                        }
                        chat.pending.firstNotNullOfOrNull { it.error }?.let {
                            Text(it, style = IosType.caption2, color = ios.orange, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    InputBar(
                        value = input,
                        onChange = { input = it },
                        busy = chat.busy,
                        thinking = chat.thinking,
                        onThinking = { chat.updateThinking(!chat.thinking) },
                        onAttachFile = { pickFile.launch(arrayOf("*/*")) },
                        onAttachPhoto = { pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onSend = { chat.send(input); input = "" },
                        onStop = { chat.stop() },
                        canSend = (input.isNotBlank() || chat.pending.isNotEmpty()) && chat.pending.none { it.uploading },
                    )
                }
            }
        }
    }

    if (picker) ModelPickerSheet(app, models, onDismiss = { picker = false })
    if (settingsOpen) ChatSettingsSheet(chat, models, app, onDismiss = { settingsOpen = false })
    editing?.let { i ->
        val m = chat.messages.getOrNull(i)
        if (m != null) EditMessageDialog(m.current.text, onDismiss = { editing = null }) { t -> chat.editAndResend(i, t); editing = null; scope.launch { } }
        else editing = null
    }
}

@Composable
private fun EmptyChat(app: AppViewModel) {
    val ios = LocalIos.current
    Column(Modifier.fillMaxWidth().padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(ContinuousRoundedRectangle(22.dp)).background(ios.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Psychology, null, Modifier.size(40.dp), tint = ios.accent)
        }
        Spacer(Modifier.size(16.dp))
        Text("What can I help with?", style = IosType.title2, color = ios.label)
        Text(
            app.model.ifBlank { "Pick a model at the top" }.substringAfterLast('/') + " · " + (app.serverKind?.label ?: "your PC"),
            style = IosType.subheadline, color = ios.secondaryLabel,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(m: ChatMessage, index: Int, chat: ChatViewModel, onEdit: () -> Unit) {
    val ios = LocalIos.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val v = m.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (v.attachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                v.attachments.forEach { a ->
                    Row(
                        Modifier.clip(ContinuousCapsule).background(ios.fill).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (a.isImage) Icons.Outlined.Image else Icons.Outlined.AttachFile, null, Modifier.size(14.dp), tint = ios.accent)
                        Spacer(Modifier.size(4.dp))
                        Text(a.name, style = IosType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                    }
                }
            }
        }
        Box {
            if (v.text.isNotBlank()) Text(
                v.text,
                style = IosType.body,
                color = Color.White,
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(ContinuousRoundedRectangle(22.dp))
                    .background(ios.userBubble)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true })
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Copy") }, { clipboard.setText(AnnotatedString(v.text)); menu = false }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                DropdownMenuItem({ Text("Edit and resend") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                DropdownMenuItem({ Text("Delete", color = ios.red) }, { menu = false; chat.deleteMessage(index) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = ios.red) })
            }
        }
        VersionSwitcher(m, index, chat)
    }
}

@Composable
private fun AssistantMessage(m: ChatMessage, index: Int, chat: ChatViewModel, streaming: Boolean) {
    val ios = LocalIos.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val v: MsgVersion = m.current
    var showThoughts by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (v.reasoning.isNotBlank()) {
            Row(
                Modifier.clip(ContinuousCapsule).clickable { showThoughts = !showThoughts }.padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp), tint = ios.secondaryLabel)
                Spacer(Modifier.size(6.dp))
                Text(
                    if (streaming && v.text.isEmpty()) "Thinking…" else v.stats?.timeToFirstTokenSec?.let { "Thought for %.1f s".format(it) } ?: "Thoughts",
                    style = IosType.subheadline, color = ios.secondaryLabel,
                )
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ios.secondaryLabel)
            }
            AnimatedVisibility(showThoughts || (streaming && v.text.isEmpty())) {
                Text(
                    v.reasoning, style = IosType.footnote, color = ios.secondaryLabel,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp).background(Color.Transparent),
                )
            }
        }
        v.tools.forEach { t ->
            var open by remember { mutableStateOf(false) }
            Column(
                Modifier.padding(vertical = 3.dp).clip(ContinuousRoundedRectangle(14.dp)).background(ios.fill)
                    .clickable(enabled = t.output != null) { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.status == "running") CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Build, null, Modifier.size(14.dp), tint = if (t.ok) ios.accent else ios.red)
                    Spacer(Modifier.size(8.dp))
                    Text(t.name, style = IosType.footnote, color = ios.label, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
                }
                if (open && t.output != null) Text(t.output, style = IosType.mono.copy(fontSize = IosType.caption.fontSize), color = ios.secondaryLabel, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (v.text.isNotBlank()) SelectionContainer { MarkdownText(v.text) }
        else if (streaming && v.reasoning.isBlank()) TypingDots()
        if (!streaming) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallAction(Icons.Outlined.ContentCopy, "Copy") { clipboard.setText(AnnotatedString(v.text)) }
                SmallAction(Icons.Outlined.Refresh, "Regenerate") { chat.regenerate(index) }
                SmallAction(Icons.Outlined.Share, "Share") {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v.text), null))
                }
                Box {
                    SmallAction(Icons.Outlined.MoreHoriz, "More") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Delete answer", color = ios.red) }, { menu = false; chat.deleteMessage(index) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = ios.red) })
                    }
                }
                VersionSwitcher(m, index, chat)
                Spacer(Modifier.weight(1f))
                listOfNotNull(v.model?.substringAfterLast('/'), v.stats?.label()).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = IosType.caption2, color = ios.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val ios = LocalIos.current
    Box(Modifier.size(34.dp).clip(ContinuousCapsule).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(18.dp), tint = ios.secondaryLabel)
    }
}

/** "‹ 2/3 ›" when a message has several versions (regenerated answers, edited messages). */
@Composable
private fun VersionSwitcher(m: ChatMessage, index: Int, chat: ChatViewModel) {
    if (m.versions.size < 2) return
    val ios = LocalIos.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous version", Modifier.size(28.dp).clip(ContinuousCapsule).clickable { chat.selectVersion(index, m.selected - 1) }, tint = if (m.selected > 0) ios.label else ios.tertiaryLabel)
        Text("${m.selected + 1}/${m.versions.size}", style = IosType.caption, color = ios.secondaryLabel)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next version", Modifier.size(28.dp).clip(ContinuousCapsule).clickable { chat.selectVersion(index, m.selected + 1) }, tint = if (m.selected < m.versions.lastIndex) ios.label else ios.tertiaryLabel)
    }
}

@Composable
private fun TypingDots() {
    val ios = LocalIos.current
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { Box(Modifier.size(8.dp).clip(ContinuousCapsule).background(ios.tertiaryLabel)) }
    }
}

@Composable
private fun InputBar(
    value: String,
    onChange: (String) -> Unit,
    busy: Boolean,
    thinking: Boolean,
    onThinking: () -> Unit,
    onAttachFile: () -> Unit,
    onAttachPhoto: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    canSend: Boolean,
) {
    val ios = LocalIos.current
    var attachMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Bottom) {
        Box {
            GlassIconButton(Icons.Outlined.Add, "Attach", { attachMenu = true }, size = 48.dp)
            DropdownMenu(attachMenu, { attachMenu = false }) {
                DropdownMenuItem({ Text("Photos") }, { attachMenu = false; onAttachPhoto() }, leadingIcon = { Icon(Icons.Outlined.Image, null) })
                DropdownMenuItem({ Text("Files") }, { attachMenu = false; onAttachFile() }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) })
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).glass(ContinuousRoundedRectangle(24.dp)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (value.isEmpty()) Text("Message", style = IosType.body, color = ios.secondaryLabel)
                BasicTextField(
                    value, onChange,
                    textStyle = IosType.body.copy(color = ios.label),
                    cursorBrush = SolidColor(ios.accent),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier.size(40.dp).clip(ContinuousCapsule).clickable(onClick = onThinking),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Psychology, if (thinking) "Thinking on" else "Thinking off", Modifier.size(22.dp), tint = if (thinking) ios.accent else ios.tertiaryLabel)
            }
            val active = busy || canSend
            Box(
                Modifier.size(40.dp).clip(ContinuousCapsule).background(if (active) ios.accent else ios.fill)
                    .clickable(enabled = active) { if (busy) onStop() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (busy) Icons.Filled.Stop else Icons.Filled.ArrowUpward, if (busy) "Stop" else "Send", Modifier.size(22.dp), tint = if (active) Color.White else ios.tertiaryLabel)
            }
        }
    }
}

@Composable
private fun EditMessageDialog(text: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(text) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = { androidx.compose.material3.OutlinedTextField(t, { t = it }, minLines = 3, maxLines = 10) },
        confirmButton = { androidx.compose.material3.TextButton({ onSave(t) }) { Text("Send") } },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text("Cancel") } },
    )
}
