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
import com.octopas.sexy.data.BridgeClient
import com.octopas.sexy.data.GitHubException
import com.octopas.sexy.data.LanScanner
import com.octopas.sexy.data.ModelList
import com.octopas.sexy.data.ServerDiscovery
import com.octopas.sexy.data.ServerKind
import com.octopas.sexy.data.ServerUrl
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
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
    /** Not found on Wi-Fi or USB, and no VPN on this phone, so the PC can't be reached. */
    data object NoTailscale : ServerStatus
    data class Offline(val reason: String) : ServerStatus
}

/**
 * Connection to the AI server on the PC (LM Studio, Ollama, llama.cpp, ...). Finds it by itself: when the app opens, when Tailscale
 * turns on or off, and when you come back to the app. It looks in three places at once: the phone itself
 * (127.0.0.1, forwarded with adb reverse), the Wi-Fi network, and Tailscale. Manual entry is only a fallback.
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
    /** Which server answered (LM Studio, Ollama, ...). */
    var serverKind by mutableStateOf(settings.serverKind)
        private set
    var connecting by mutableStateOf(false)
        private set
    var connectError by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<ServerStatus>(ServerStatus.Checking)
        private set
    val tailscaleOn: Boolean get() = sexy.tailscale.on.value
    /** The PC bridge (chats in LM Studio's folder, uploads, terminal) answers. */
    var bridgeOnline by mutableStateOf(false)
        private set

    fun checkBridge() {
        val url = settings.bridgeUrl ?: BridgeClient.urlFor(settings.serverUrl) ?: return
        viewModelScope.launch { bridgeOnline = BridgeClient(url).health() }
    }

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
                    // Maybe connected over Wi-Fi or USB, which still works: check, and search again if not.
                    checkStatus()
                }
            }
        }
    }

    fun client(): AiClient = AiClient(settings.serverUrl ?: "", settings.apiKey, kind = settings.serverKind)

    /** Looks for the PC everywhere it could be and connects to the first place that answers. */
    fun findServer() {
        if (search?.isActive == true) return
        search = viewModelScope.launch {
            status = ServerStatus.Checking
            connecting = true
            connectError = null
            when (val r = searchEverywhere()) {
                is ServerDiscovery.Result.Success -> useServer(r.found.url, r.found.models, settings.apiKey, r.found.kind)
                is ServerDiscovery.Result.Failed -> {
                    val reason = r.refused?.let { friendlyError(it.error) }
                        ?: if (!tailscaleOn) "Not found on this Wi-Fi or over USB, and Tailscale is off. Turn Tailscale on and SEXY connects by itself."
                        else "Couldn't find an AI server on your PC. Is the PC on, and is its AI server (llama.cpp, LM Studio, Ollama...) running?"
                    status = if (r.refused == null && !tailscaleOn) ServerStatus.NoTailscale else ServerStatus.Offline(reason)
                    connectError = reason
                }
            }
            connecting = false
        }
    }

    /**
     * Searches the known addresses (last one used, 127.0.0.1, Tailscale names) and the Wi-Fi network at
     * the same time; whichever finds a server first wins.
     */
    private suspend fun searchEverywhere(): ServerDiscovery.Result = coroutineScope {
        val named = ServerDiscovery.candidates(settings.serverUrl, settings.extraHosts)
        val searches: MutableList<Deferred<ServerDiscovery.Result>> = mutableListOf(
            async { ServerDiscovery.find(named, settings.apiKey) },
            async {
                val hits = LanScanner.scan(getApplication()).filter { it !in named }
                if (hits.isEmpty()) ServerDiscovery.Result.Failed(null, emptyList())
                else ServerDiscovery.find(hits, settings.apiKey)
            },
        )
        val failures = mutableListOf<ServerDiscovery.Result.Failed>()
        while (searches.isNotEmpty()) {
            val (done, r) = select { searches.forEach { d -> d.onAwait { d to it } } }
            searches -= done
            if (r is ServerDiscovery.Result.Success) {
                searches.forEach { it.cancel() }
                return@coroutineScope r
            }
            failures += r as ServerDiscovery.Result.Failed
        }
        ServerDiscovery.Result.Failed(failures.firstNotNullOfOrNull { it.refused }, failures.flatMap { it.tried })
    }

    /** Manual fallback: the address (and optional API key) the user typed. */
    fun connect(addressInput: String, keyInput: String) {
        val candidates = ServerUrl.candidates(addressInput)
        if (candidates.isEmpty()) {
            connectError = "That doesn't look like an address. Example: desktop-d0efhda, 192.168.1.20:9931 or 127.0.0.1:9931"
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
                    useServer(r.found.url, r.found.models, key, r.found.kind)
                }
                is ServerDiscovery.Result.Failed -> connectError = r.refused?.let { "${friendlyError(it.error)}\n(at ${it.url})" }
                    ?: ("Nothing answered at:\n" + r.tried.joinToString("\n") { "• $it" } +
                        "\n\nCheck that the AI server (llama.cpp, LM Studio, Ollama, ...) is running and reachable: same Wi-Fi, " +
                        "adb reverse for 127.0.0.1, or Tailscale on here and on the PC.")
            }
            connecting = false
        }
    }

    private fun useServer(url: String, found: List<AiModel>, key: String?, kind: ServerKind) {
        settings.serverUrl = url
        settings.serverKind = kind
        serverKind = kind
        settings.apiKey = key
        models = found
        val pick = ModelList.choose(found, settings.modelId)
        if (pick != null) settings.modelId = pick
        serverUrl = url
        model = settings.modelId ?: ""
        status = if (found.isEmpty()) {
            ServerStatus.Offline("${kind.label} is running but has no chat models. Download one in ${kind.label}.")
        } else ServerStatus.Online
        lastOnlineAt = System.currentTimeMillis()
        checkBridge()
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
                val info = client().detect()
                useServer(settings.serverUrl ?: "", info.models, settings.apiKey, info.kind)
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
        serverKind = null
        models = emptyList()
        connecting = false
        val hint = "Type the other PC's address below (Tailscale name, Wi-Fi IP or 127.0.0.1:9931), or search again."
        status = ServerStatus.Offline(hint)
        connectError = hint
    }
}

fun friendlyError(e: Throwable): String = when (e) {
    is AiException -> e.message ?: "AI server error"
    is GitHubException -> e.message ?: "GitHub error"
    is UnknownHostException -> "Can't find your PC. Is Tailscale turned on on this phone?"
    is ConnectException -> "Can't connect. Is the PC on, the AI server (llama.cpp, LM Studio, ...) running, and the PC reachable (same Wi-Fi, adb reverse or Tailscale)?"
    is SocketTimeoutException -> "No answer in time. Is the PC on and on the same Wi-Fi or Tailscale?"
    is NoRouteToHostException -> "Can't reach the PC. Check that it's on the same Wi-Fi, or that Tailscale is connected on both devices."
    is SSLException -> "Secure-connection problem. Use http:// - Tailscale already encrypts the connection."
    else -> e.message ?: e.toString()
}

/** Errors that mean "couldn't reach the PC" (as opposed to the model saying something went wrong). */
fun isConnectionProblem(e: Throwable): Boolean =
    e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ||
        (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
