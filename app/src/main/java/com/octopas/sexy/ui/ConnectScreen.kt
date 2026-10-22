package com.octopas.sexy.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octopas.sexy.AppViewModel
import com.octopas.sexy.R

@Composable
fun ConnectScreen(vm: AppViewModel) {
    var address by rememberSaveable { mutableStateOf(vm.serverUrl) }
    var key by rememberSaveable { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val submit = { if (address.isNotBlank() && !vm.connecting) vm.connect(address, key) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Box(
                Modifier.size(112.dp).clip(CircleShape).background(Color(0xFF15121A)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.size(112.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("SEXY", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            Text(
                "Your own AI, running on your PC",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))

            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server address") },
                placeholder = { Text("desktop-d0efhda:3030 or 100.x.x.x") },
                leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                supportingText = { Text("Your PC's Tailscale name or IP. Port 3030 is added if you leave it out.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                leadingIcon = { Icon(Icons.Outlined.Key, null) },
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show key")
                    }
                },
                supportingText = { Text("Copy it from D:\\AI\\api-key.txt on your PC") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )

            vm.connectError?.let { err ->
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = submit,
                enabled = address.isNotBlank() && !vm.connecting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (vm.connecting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                else Text("Connect", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Turn on Tailscale on this phone first.\nOn the PC, '2-START AI SERVER.bat' must be running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
