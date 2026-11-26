package com.octopas.sexy

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octopas.sexy.data.AiClient
import com.octopas.sexy.data.AiException
import com.octopas.sexy.data.AiModel
import com.octopas.sexy.data.GitHubException
import com.octopas.sexy.data.ModelList
import com.octopas.sexy.data.ServerDiscovery
import com.octopas.sexy.data.ServerUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface ServerStatus {
    /** Looking for the PC on the tailnet. */
    data object Checking : ServerStatus
    data object Online : ServerStatus
    /** No VPN on this phone, so the PC can't be reached. */
    data object NoTailscale : ServerStatus
    data class Offline(val reason: String) : ServerStatus
}

/**
 * Connection to LM Studio on the PC. Finds it by itself: when the app opens, when Tailscale
 * turns on, and when you come back to the app. Manual entry is only a fallback.
 */
class AppViewModel @JvmOverloads constructor(
    app: Application,
    /** False in screenshot tests: no network search, fixed state. */
    private val autoSearch: Boolean = true,
) : AndroidViewModel(app) {
    private val sexy = app as SexyApp
    val settings = sexy.settings

    /** True once a server has worked at least once (then the chat opens straight away). */
    var connected by mutableStateOf(settings.serverUrl != null)
        private set
    var serverUrl by mutableStateOf(settings.serverUrl ?: "")
        private set
    var model by mutableStateOf(settings.modelId ?: "")
        private set
    var models by mutableStateOf<List<AiModel>>(emptyList())
        private set
    var connecting by mutableStateOf(false)
        private set
    var connectError by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<ServerStatus>(ServerStatus.Checking)
        private set
    val tailscaleOn: Boolean get() = sexy.tailscale.on.value

    private var search: Job? = null
    private var lastOnlineAt = 0L

    init {
        if (!autoSearch) {
            status = if (connected) ServerStatus.Online else ServerStatus.NoTailscale
        } else {
            findServer()
            watchTailscale()
        }
    }

    private fun watchTailscale() {
        viewModelScope.launch {
            // Tailscale switched on or off while the app is open.
            sexy.tailscale.on.drop(1).collect { on ->
                if (on) {
                    delay(1500) // give the VPN a moment to set up its routes and DNS
                    findServer()
                } else if (connected) {
                    status = ServerStatus.NoTailscale
                }
            }
        }
    }

    fun client(): AiClient = AiClient(settings.serverUrl ?: "", settings.apiKey)

    /** Looks for the PC everywhere it could be and connects to the first place that answers. */
    fun findServer() {
        if (search?.isActive == true) return
        search = viewModelScope.launch {
            status = ServerStatus.Checking
            connecting = true
            connectError = null
            val candidates = ServerDiscovery.candidates(settings.serverUrl, settings.extraHosts)
            when (val r = ServerDiscovery.find(candidates, settings.apiKey)) {
                is ServerDiscovery.Result.Success -> useServer(r.found.url, r.found.models, settings.apiKey)
                is ServerDiscovery.Result.Failed -> {
                    val reason = r.refused?.let { friendlyError(it.error) }
                        ?: if (!tailscaleOn) "Tailscale is off on this phone. Turn it on and SEXY connects by itself."
                        else "Couldn't find LM Studio on your PC. Is the PC on, and is LM Studio's server running?"
                    status = if (r.refused == null && !tailscaleOn) ServerStatus.NoTailscale else ServerStatus.Offline(reason)
                    connectError = reason
                }
            }
            connecting = false
        }
    }

    /** Manual fallback: the address (and optional API key) the user typed. */
    fun connect(addressInput: String, keyInput: String) {
        val candidates = ServerUrl.candidates(addressInput)
        if (candidates.isEmpty()) {
            connectError = "That doesn't look like an address. Example: desktop-d0efhda or 100.101.102.103"
            return
        }
        val key = keyInput.trim().ifBlank { null }
        search?.cancel()
        search = viewModelScope.launch {
            connecting = true
            connectError = null
            when (val r = ServerDiscovery.firstWorking(candidates, key)) {
                is ServerDiscovery.Result.Success -> {
                    // Remember a typed name so automatic search finds this PC next time too.
                    val host = addressInput.trim().substringAfter("://").substringBefore('/').substringBefore(':')
                    if (host.isNotBlank() && host !in ServerDiscovery.DEFAULT_HOSTS && host !in settings.extraHosts) {
                        settings.extraHosts = settings.extraHosts + host
                    }
                    useServer(r.found.url, r.found.models, key)
                }
                is ServerDiscovery.Result.Failed -> connectError = r.refused?.let { "${friendlyError(it.error)}\n(at ${it.url})" }
                    ?: ("Nothing answered at:\n" + r.tried.joinToString("\n") { "• $it" } +
                        "\n\nCheck that Tailscale is on here and on the PC, and that LM Studio's server is running.")
            }
            connecting = false
        }
    }

    private fun useServer(url: String, found: List<AiModel>, key: String?) {
        settings.serverUrl = url
        settings.apiKey = key
        models = found
        val pick = ModelList.choose(found, settings.modelId)
        if (pick != null) settings.modelId = pick
        serverUrl = url
        model = settings.modelId ?: ""
        status = if (found.isEmpty()) {
            ServerStatus.Offline("LM Studio is running but has no chat models. Download one in LM Studio.")
        } else ServerStatus.Online
        lastOnlineAt = System.currentTimeMillis()
        connected = true
        connectError = null
    }

    fun selectModel(id: String) {
        settings.modelId = id
        model = id
    }

    /** Quick re-check (status line tapped, or app opened again). Falls back to a full search. */
    fun checkStatus() {
        if (!connected) return findServer()
        if (search?.isActive == true) return
        search = viewModelScope.launch {
            status = ServerStatus.Checking
            try {
                val found = client().models()
                useServer(settings.serverUrl ?: "", found, settings.apiKey)
            } catch (e: Exception) {
                search = null
                findServer()
            }
        }
    }

    /** Called when the app comes back to the screen. */
    fun onResume() {
        if (!autoSearch) return
        val fresh = status == ServerStatus.Online && System.currentTimeMillis() - lastOnlineAt < 60_000
        if (!fresh) checkStatus()
    }

    /** A chat request failed to reach the PC: look for it again (its address may have changed). */
    fun connectionLost() {
        status = ServerStatus.Offline("Lost the connection to your PC.")
        findServer()
    }

    fun disconnect() {
        search?.cancel()
        settings.disconnectServer()
        connected = false
        models = emptyList()
        connecting = false
        val hint = "Type the other PC's Tailscale name below, or search again."
        status = ServerStatus.Offline(hint)
        connectError = hint
    }
}

fun friendlyError(e: Throwable): String = when (e) {
    is AiException -> e.message ?: "AI server error"
    is GitHubException -> e.message ?: "GitHub error"
    is UnknownHostException -> "Can't find your PC. Is Tailscale turned on on this phone?"
    is ConnectException -> "Can't connect. Is the PC on, Tailscale connected, and LM Studio's server running?"
    is SocketTimeoutException -> "No answer in time. Is the PC on and connected to Tailscale?"
    is NoRouteToHostException -> "Can't reach the PC. Check that Tailscale is connected on both devices."
    is SSLException -> "Secure-connection problem. Use http:// - Tailscale already encrypts the connection."
    else -> e.message ?: e.toString()
}

/** Errors that mean "couldn't reach the PC" (as opposed to the model saying something went wrong). */
fun isConnectionProblem(e: Throwable): Boolean =
    e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ||
        (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
