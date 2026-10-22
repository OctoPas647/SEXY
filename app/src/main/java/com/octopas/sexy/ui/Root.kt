package com.octopas.sexy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.ChatViewModel
import com.octopas.sexy.GitHubViewModel

enum class Tab(val label: String, val icon: ImageVector) {
    Chat("Chat", Icons.AutoMirrored.Outlined.Chat),
    Code("GitHub", Icons.Outlined.Code),
    Settings("Settings", Icons.Outlined.Settings),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SexyRoot(app: AppViewModel = viewModel()) {
    if (!app.connected) {
        ConnectScreen(app)
        return
    }
    val chat: ChatViewModel = viewModel()
    val github: GitHubViewModel = viewModel()
    var tab by rememberSaveable { mutableStateOf(Tab.Chat) }
    LaunchedEffect(Unit) { app.checkStatus() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Chat -> ChatScreen(app, chat)
                Tab.Code -> GitHubScreen(github)
                Tab.Settings -> SettingsScreen(app, chat, github, onOpenGitHub = { tab = Tab.Code })
            }
        }
        // Hide the bottom bar while typing so the keyboard sits right under the text field.
        if (!WindowInsets.isImeVisible) {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    }
}
