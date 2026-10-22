package com.octopas.sexy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.octopas.sexy.GitHubViewModel
import com.octopas.sexy.ServerStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppViewModel, chat: ChatViewModel, github: GitHubViewModel, onOpenGitHub: () -> Unit) {
    val context = LocalContext.current
    var systemPrompt by remember { mutableStateOf(app.settings.systemPrompt) }
    var confirmDelete by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("AI server") {
                Text(app.serverUrl, style = MaterialTheme.typography.bodyLarge)
                StatusLine(app)
                (app.status as? ServerStatus.Offline)?.let {
                    Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.checkStatus() }) { Text("Test connection") }
                    OutlinedButton(onClick = { chat.stop(); app.disconnect() }) { Text("Change server") }
                }
            }

            Section("Chat") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Think before answering")
                        Text(
                            "Smarter answers, but slower. You can also toggle it next to the message box.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = chat.thinking, onCheckedChange = chat::updateThinking)
                }
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it; app.settings.systemPrompt = it },
                    label = { Text("Custom instructions (optional)") },
                    placeholder = { Text("e.g. Answer briefly. I'm building Android apps in Kotlin.") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { confirmDelete = true }) { Text("Delete all chats") }
            }

            Section("GitHub") {
                if (github.login != null) {
                    Text("Signed in as @${github.login}")
                    OutlinedButton(onClick = { github.signOut() }) { Text("Sign out / change token") }
                } else {
                    Text("Not connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOpenGitHub) { Text("Connect GitHub") }
                }
            }

            Section("About") {
                Text("SEXY $version")
                Text(
                    "Talks only to your own AI server and to GitHub. Your API key and GitHub token are encrypted with the phone's keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all chats?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { chat.deleteAll(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
