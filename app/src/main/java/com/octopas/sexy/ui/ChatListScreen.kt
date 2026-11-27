package com.octopas.sexy.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.ChatViewModel
import com.octopas.sexy.data.ChatSummary
import com.octopas.sexy.data.SyncState
import com.octopas.sexy.ui.glass.GlassIconButton
import com.octopas.sexy.ui.glass.LocalBackdrop
import com.octopas.sexy.ui.theme.IosType
import com.octopas.sexy.ui.theme.LocalIos
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ChatListScreen(app: AppViewModel, chat: ChatViewModel) {
    val ios = LocalIos.current
    val backdrop = rememberLayerBackdrop()
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ChatSummary?>(null) }
    val sync by chat.syncState.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val shown = chat.chats.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || (it.model ?: "").contains(query, true) }
    val groups = groupChats(shown)

    Box(Modifier.fillMaxSize().background(ios.groupedBackground)) {
        LazyColumn(
            Modifier.fillMaxSize().layerBackdrop(backdrop),
            contentPadding = PaddingValues(top = top + 60.dp, bottom = LocalTabBarSpace.current + 24.dp),
        ) {
            item {
                LargeTitle(
                    "Chats",
                    subtitle = when (sync) {
                        SyncState.SYNCED -> if (app.settings.keepLocalCopy) "On your PC · copy on this phone" else "Stored on your PC · LM Studio"
                        SyncState.SYNCING -> "Syncing…"
                        SyncState.PENDING -> "Waiting to save to your PC"
                        SyncState.PC_OFFLINE -> "Your PC is offline"
                        SyncState.NO_BRIDGE -> "Chat bridge not running on the PC"
                        SyncState.ON_PHONE -> "Stored on this phone · ${app.serverKind?.label ?: "AI server"}"
                    },
                )
            }
            item { IosSearchField(query, { query = it }, "Search chats", Modifier.padding(bottom = 8.dp)) }
            if (sync == SyncState.NO_BRIDGE || sync == SyncState.PC_OFFLINE) item {
                IosSection(footer = "Run SEXY-LMSTUDIO.bat in D:\\AI\\LMStudio on the PC once. It starts the bridge that keeps your chats in LM Studio's folder.") {
                    IosRow(
                        if (sync == SyncState.NO_BRIDGE) "Chats can't be saved yet" else "Can't reach your PC",
                        subtitle = if (app.settings.keepLocalCopy) "New chats are kept on this phone and sent to the PC later."
                        else "New chats wait in the app until the PC is back. Turn on \"Keep a copy on this phone\" in Settings to keep them safe.",
                        icon = Icons.Outlined.CloudOff, iconColor = ios.orange, divider = false,
                        onClick = { chat.syncNow() },
                    )
                }
            }
            if (shown.isEmpty() && !chat.listLoading) item {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (query.isBlank()) "No chats yet" else "No results", style = IosType.title3, color = ios.label)
                    Text(
                        if (query.isBlank()) "Tap the pencil to start one." else "Try other words.",
                        style = IosType.subheadline, color = ios.secondaryLabel,
                    )
                }
            }
            groups.forEach { (title, list) ->
                item(key = "g-$title") {
                    IosSection(header = title) {
                        list.forEachIndexed { i, c ->
                            ChatRow(
                                c, divider = i < list.lastIndex,
                                onOpen = { chat.open(c.id) },
                                onRename = { renaming = c },
                                onPin = { chat.pinChat(c.id, !c.pinned) },
                                onDelete = { chat.deleteChat(c.id) },
                            )
                        }
                    }
                }
            }
        }
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassIconButton(Icons.Outlined.Refresh, "Refresh", { chat.refreshList(); chat.syncNow() })
                GlassIconButton(Icons.Outlined.Edit, "New chat", { chat.newChat() }, accent = true)
            }
        }
    }

    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ chat.renameChat(c.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    c: ChatSummary,
    divider: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    val ios = LocalIos.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(ContinuousRoundedRectangle(12.dp)).background(ios.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(c.title.firstOrNull()?.uppercase() ?: "?", style = IosType.headline, color = ios.accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.pinned) Icon(Icons.Outlined.PushPin, null, Modifier.size(14.dp).padding(end = 2.dp), tint = ios.orange)
                        Text(c.title, style = IosType.headline, color = ios.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(shortTime(maxOf(c.updatedAt, c.createdAt)), style = IosType.footnote, color = ios.secondaryLabel)
                    }
                    Text(
                        listOfNotNull(c.model, c.folder.takeIf { it.isNotBlank() }?.let { "📁 $it" }, if (c.tokenCount > 0) "${c.tokenCount} tokens" else null)
                            .joinToString(" · ").ifBlank { "LM Studio chat" },
                        style = IosType.subheadline, color = ios.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (divider) Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(ios.separator))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (c.pinned) "Unpin" else "Pin") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = { menu = false; onPin() })
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(
                text = { Text("Delete", color = ios.red) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = ios.red) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

/** Pinned first, then like iOS: Today, Yesterday, Previous 7 Days, Previous 30 Days, then by month. */
fun groupChats(list: List<ChatSummary>, now: Long = System.currentTimeMillis()): List<Pair<String, List<ChatSummary>>> {
    val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val today = cal.timeInMillis
    val day = 86_400_000L
    val month = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val out = LinkedHashMap<String, MutableList<ChatSummary>>()
    list.filter { it.pinned }.takeIf { it.isNotEmpty() }?.let { out["Pinned"] = it.toMutableList() }
    for (c in list.filter { !it.pinned }) {
        val t = maxOf(c.updatedAt, c.createdAt)
        val key = when {
            t >= today -> "Today"
            t >= today - day -> "Yesterday"
            t >= today - 7 * day -> "Previous 7 Days"
            t >= today - 30 * day -> "Previous 30 Days"
            else -> month.format(Date(t))
        }
        out.getOrPut(key) { mutableListOf() } += c
    }
    return out.map { it.key to it.value }
}

fun shortTime(t: Long, now: Long = System.currentTimeMillis()): String {
    if (t <= 0) return ""
    val d = Date(t)
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(d) == it.format(Date(now)) }
    return if (sameDay) SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
    else if (now - t < 7 * 86_400_000L) SimpleDateFormat("EEE", Locale.getDefault()).format(d)
    else SimpleDateFormat("d MMM", Locale.getDefault()).format(d)
}
