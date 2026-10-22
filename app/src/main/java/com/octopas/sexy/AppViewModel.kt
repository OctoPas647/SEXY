package com.octopas.sexy

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octopas.sexy.data.AiClient
import com.octopas.sexy.data.AiException
import com.octopas.sexy.data.GitHubException
import com.octopas.sexy.data.ServerUrl
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface ServerStatus {
    data object Checking : ServerStatus
    data object Online : ServerStatus
    data class Offline(val reason: String) : ServerStatus
}

/** Connection to the AI server on the PC (the "Immich-style" login). */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = (app as SexyApp).settings

    var connected by mutableStateOf(settings.serverUrl != null)
        private set
    var serverUrl by mutableStateOf(settings.serverUrl ?: "")
        private set
    var model by mutableStateOf(settings.modelId ?: "")
        private set
    var connecting by mutableStateOf(false)
        private set
    var connectError by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<ServerStatus>(ServerStatus.Checking)
        private set

    fun client(): AiClient = AiClient(settings.serverUrl ?: "", settings.apiKey)

    fun connect(addressInput: String, keyInput: String) {
        val url = ServerUrl.normalize(addressInput)
        if (url == null) {
            connectError = "That doesn't look like an address. Example: desktop-d0efhda:3030 or 100.101.102.103"
            return
        }
        val key = keyInput.trim()
        viewModelScope.launch {
            connecting = true
            connectError = null
            try {
                val models = AiClient(url, key.ifBlank { null }).listModels()
                settings.serverUrl = url
                settings.apiKey = key
                settings.modelId = models.firstOrNull() ?: "default"
                serverUrl = url
                model = settings.modelId ?: ""
                status = ServerStatus.Online
                connected = true
            } catch (e: Exception) {
                connectError = friendlyError(e)
            } finally {
                connecting = false
            }
        }
    }

    fun checkStatus() {
        if (!connected) return
        viewModelScope.launch {
            status = ServerStatus.Checking
            status = try {
                val models = client().listModels()
                models.firstOrNull()?.let { if (it != model) { model = it; settings.modelId = it } }
                ServerStatus.Online
            } catch (e: Exception) {
                ServerStatus.Offline(friendlyError(e))
            }
        }
    }

    fun disconnect() {
        settings.disconnectServer()
        connected = false
        status = ServerStatus.Checking
        connectError = null
    }
}

fun friendlyError(e: Throwable): String = when (e) {
    is AiException -> e.message ?: "AI server error"
    is GitHubException -> e.message ?: "GitHub error"
    is UnknownHostException -> "Can't find that address. Is Tailscale turned on on this phone, and is the name right?"
    is ConnectException -> "Can't connect. Is the PC on, Tailscale connected, and '2-START AI SERVER.bat' running?"
    is SocketTimeoutException -> "No answer in time. Is the PC on and connected to Tailscale?"
    is NoRouteToHostException -> "Can't reach the PC. Check that Tailscale is connected on both devices."
    is SSLException -> "Secure-connection problem. Use http:// - Tailscale already encrypts the connection."
    else -> e.message ?: e.toString()
}
