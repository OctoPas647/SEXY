package com.octopas.sexy.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.ChatViewModel
import com.octopas.sexy.ServerStatus
import com.octopas.sexy.UiMessage
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(app: AppViewModel, vm: ChatViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SEXY", fontWeight = FontWeight.Black)
                        StatusLine(app)
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) { Icon(Icons.Outlined.History, "Chat history") }
                    IconButton(onClick = { vm.newChat(); input = "" }) { Icon(Icons.Outlined.AddComment, "New chat") }
                },
            )
        },
        bottomBar = {
            ChatInput(
                value = input,
                onValueChange = { input = it },
                busy = vm.busy,
                thinking = vm.thinking,
                onThinking = vm::updateThinking,
                onSend = { vm.send(input); input = "" },
                onStop = vm::stop,
            )
        },
    ) { pad ->
        if (vm.messages.isEmpty()) {
            EmptyChat(Modifier.padding(pad)) { input = it }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                reverseLayout = true, // newest at the bottom, stays pinned while streaming
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
            ) {
                items(vm.messages.asReversed(), key = { it.key }) { m -> MessageItem(m) }
            }
        }
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (vm.conversations.isEmpty()) {
                Text(
                    "No saved chats yet.",
                    Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(vm.conversations, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.updatedAt)))
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.delete(c) }) { Icon(Icons.Outlined.Delete, "Delete chat") }
                        },
                        modifier = Modifier.clickable { vm.open(c); showHistory = false },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun StatusLine(app: AppViewModel) {
    val (color, text) = when (val s = app.status) {
        ServerStatus.Checking -> MaterialTheme.colorScheme.outline to "connecting…"
        ServerStatus.Online -> Color(0xFF22C55E) to app.model
        is ServerStatus.Offline -> MaterialTheme.colorScheme.error to "offline · tap to retry"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { app.checkStatus() },
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun EmptyChat(modifier: Modifier, onPick: (String) -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Psychology, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Ask your PC anything", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Answers are generated on your own GPU at home.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        listOf(
            "Explain Kotlin coroutines like I'm new to them",
            "Write 3 matura-style math questions with solutions",
            "How do I make my Android app work offline?",
        ).forEach { s ->
            SuggestionChip(onClick = { onPick(s) }, label = { Text(s) })
        }
    }
}

@Composable
private fun MessageItem(m: UiMessage) {
    if (m.role == "user") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                SelectionContainer {
                    Text(m.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.reasoning.isNotEmpty()) ThinkingBlock(m.reasoning, live = m.streaming && m.content.isEmpty())
        if (m.streaming && m.content.isEmpty() && m.reasoning.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Waiting for your PC…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (m.content.isNotEmpty()) SelectionContainer { MarkdownText(m.content) }
        m.error?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        if (!m.streaming && (m.content.isNotEmpty() || m.stats != null)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                m.stats?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (m.content.isNotEmpty()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(m.content)) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.ContentCopy, "Copy answer", Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ThinkingBlock(reasoning: String, live: Boolean) {
    var open by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        onClick = { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (live) "Thinking…" else "Thought process",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (live) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            when {
                open -> Text(
                    reasoning.trim(),
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                live -> Text(
                    reasoning.trim().takeLast(220),
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    thinking: Boolean,
    onThinking: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconToggleButton(checked = thinking, onCheckedChange = onThinking) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = if (thinking) "Thinking on" else "Thinking off",
                    tint = if (thinking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message your PC…") },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Icon(Icons.Filled.Stop, "Stop") }
            } else {
                FilledIconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}
