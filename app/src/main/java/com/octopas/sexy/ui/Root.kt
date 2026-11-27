package com.octopas.sexy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.ChatViewModel
import com.octopas.sexy.GitHubViewModel
import com.octopas.sexy.ModelsViewModel
import com.octopas.sexy.TerminalViewModel
import com.octopas.sexy.ui.glass.LiquidBottomTab
import com.octopas.sexy.ui.glass.LiquidBottomTabs
import com.octopas.sexy.ui.theme.IosType
import com.octopas.sexy.ui.theme.LocalIos

enum class Tab(val label: String, val icon: ImageVector) {
    Chats("Chats", Icons.AutoMirrored.Outlined.Chat),
    Models("Models", Icons.Outlined.Memory),
    Terminal("Terminal", Icons.Outlined.Terminal),
    Code("GitHub", Icons.Outlined.Code),
    Settings("Settings", Icons.Outlined.Settings),
}

/** Space lists leave at the bottom so their last rows can scroll above the floating tab bar. */
val LocalTabBarSpace = staticCompositionLocalOf<Dp> { 0.dp }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SexyRoot(app: AppViewModel = viewModel()) {
    // Coming back to the app: make sure the PC is still there (or find it again).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { app.onResume() }
    if (!app.connected) {
        ConnectScreen(app)
        return
    }
    val chat: ChatViewModel = viewModel()
    val models: ModelsViewModel = viewModel()
    val terminal: TerminalViewModel = viewModel()
    val github: GitHubViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(chat) {
        chat.onConnectionProblem = app::connectionLost
        chat.modelProvider = { app.model.ifBlank { "default" } }
    }
    LaunchedEffect(app.serverUrl, app.serverKind) { models.refresh() }

    val ios = LocalIos.current
    val backdrop = rememberLayerBackdrop()
    val inChat = Tab.entries[tab] == Tab.Chats && chat.isOpen
    val showTabs = !inChat && !WindowInsets.isImeVisible

    Box(Modifier.fillMaxSize().background(ios.groupedBackground)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalTabBarSpace provides if (showTabs) 100.dp else 0.dp) {
                when (Tab.entries[tab]) {
                    Tab.Chats -> if (chat.isOpen) ChatScreen(app, chat, models) else ChatListScreen(app, chat)
                    Tab.Models -> ModelsScreen(app, models, onUseInChat = { key -> app.selectModel(key); tab = 0 })
                    Tab.Terminal -> TerminalScreen(app, terminal)
                    Tab.Code -> Box(Modifier.fillMaxSize().padding(bottom = LocalTabBarSpace.current)) { GitHubScreen(github) }
                    Tab.Settings -> SettingsScreen(app, chat, models)
                }
            }
        }
        if (showTabs) {
            LiquidBottomTabs(
                selectedTabIndex = { tab },
                onTabSelected = { tab = it },
                backdrop = backdrop,
                tabsCount = Tab.entries.size,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Tab.entries.forEachIndexed { i, t ->
                    LiquidBottomTab({ tab = i }) {
                        Icon(t.icon, null, Modifier.size(24.dp), tint = ios.label)
                        Text(t.label, style = IosType.caption2.copy(fontSize = 10.sp), color = ios.label, maxLines = 1)
                    }
                }
            }
        }
    }
}
