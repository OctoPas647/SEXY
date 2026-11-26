package com.octopas.sexy.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
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
import com.octopas.sexy.ServerStatus

/** Opens the Tailscale app if it's installed. Returns false if it isn't. */
fun openTailscale(context: Context): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn") ?: return false
    return runCatching { context.startActivity(intent) }.isSuccess
}

@Composable
fun ConnectScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val submit = { if (address.isNotBlank() && !vm.connecting) vm.connect(address, key) }
    val noTailscale = vm.status == ServerStatus.NoTailscale

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

            // Automatic search
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        vm.connecting -> {
                            CircularProgressIndicator(Modifier.size(36.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("Looking for your PC…", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Checking LM Studio on your Tailscale network",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        noTailscale -> {
                            Icon(Icons.Outlined.VpnLock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(10.dp))
                            Text("Turn on Tailscale", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "SEXY connects to your PC by itself as soon as Tailscale is on.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            FilledTonalButton(onClick = { if (!openTailscale(context)) vm.findServer() }) {
                                Text("Open Tailscale")
                            }
                        }
                        else -> {
                            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(10.dp))
                            Text("Couldn't find your PC", style = MaterialTheme.typography.titleMedium)
                            vm.connectError?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = vm::findServer) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Search again")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { manual = !manual }) {
                Text(if (manual) "Hide manual setup" else "Enter address manually")
            }

            AnimatedVisibility(manual) {
                Column {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("PC address") },
                        placeholder = { Text("desktop-d0efhda or 100.x.x.x") },
                        leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                        supportingText = { Text("Tailscale name or IP. Ports 1234 (LM Studio) and 3030 are tried.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API key (optional)") },
                        leadingIcon = { Icon(Icons.Outlined.Key, null) },
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show key")
                            }
                        },
                        supportingText = { Text("Only if you turned on API tokens in LM Studio") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = submit,
                        enabled = address.isNotBlank() && !vm.connecting,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Connect") }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "On the PC: LM Studio with its server running (D:\\AI\\tools\\SEXY-SERVER-SETUP.bat sets it up).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
