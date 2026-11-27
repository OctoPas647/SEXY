package com.octopas.sexy

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octopas.sexy.data.BridgeClient
import com.octopas.sexy.data.DownloadJob
import com.octopas.sexy.data.LmModel
import com.octopas.sexy.data.LmStudioApi
import com.octopas.sexy.data.LoadConfig
import com.octopas.sexy.data.PluginInfo
import com.octopas.sexy.data.ServerKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** LM Studio's model manager on the phone: what's on disk, what's loaded, load settings, downloads, plugins. */
class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as SexyApp).settings

    var models by mutableStateOf<List<LmModel>>(emptyList())
        internal set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    /** Model key -> what's happening to it ("Loading…", "Ejecting…"). */
    var working by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    val downloads = mutableStateListOf<DownloadJob>()
    var plugins by mutableStateOf<List<PluginInfo>>(emptyList())
        internal set

    val isLmStudio: Boolean get() = settings.serverKind == ServerKind.LM_STUDIO

    private var poller: Job? = null

    private fun api() = LmStudioApi(settings.serverUrl ?: "", settings.apiKey)

    fun refresh() {
        if (!isLmStudio) return
        viewModelScope.launch {
            loading = true
            runCatching { api().models() }
                .onSuccess { models = it; error = null }
                .onFailure { error = friendlyError(it) }
            loading = false
        }
        refreshPlugins()
    }

    fun refreshPlugins() {
        val url = settings.bridgeUrl ?: BridgeClient.urlFor(settings.serverUrl) ?: return
        viewModelScope.launch { runCatching { BridgeClient(url).plugins() }.onSuccess { plugins = it } }
    }

    fun loadConfigFor(m: LmModel): LoadConfig {
        settings.loadConfig(m.key)?.let { return it }
        val inst = m.loaded.firstOrNull()
        return LoadConfig(
            contextLength = inst?.contextLength ?: minOf(m.maxContext ?: 8192, 16384),
            flashAttention = inst?.flashAttention ?: true,
            kvOnGpu = inst?.kvOnGpu ?: true,
            numExperts = inst?.numExperts,
            evalBatchSize = inst?.evalBatchSize,
        )
    }

    fun saveLoadConfig(m: LmModel, cfg: LoadConfig) = settings.setLoadConfig(m.key, cfg)

    var unloadOthers: Boolean
        get() = settings.unloadOthersOnLoad
        set(v) { settings.unloadOthersOnLoad = v }

    fun load(m: LmModel, cfg: LoadConfig, then: () -> Unit = {}) {
        settings.setLoadConfig(m.key, cfg)
        viewModelScope.launch {
            working = working + (m.key to "Loading…")
            try {
                val api = api()
                // Reloading with new settings: eject the old copy first.
                m.loaded.forEach { runCatching { api.unload(it.id) } }
                if (settings.unloadOthersOnLoad) {
                    models.filter { it.key != m.key }.flatMap { it.loaded }.forEach { runCatching { api.unload(it.id) } }
                }
                api.load(m.key, cfg)
                error = null
                then()
            } catch (e: Exception) {
                error = "Couldn't load ${m.displayName}: ${friendlyError(e)}"
            } finally {
                working = working - m.key
                refresh()
            }
        }
    }

    fun unload(m: LmModel) {
        viewModelScope.launch {
            working = working + (m.key to "Ejecting…")
            runCatching { m.loaded.forEach { api().unload(it.id) } }.onFailure { error = friendlyError(it) }
            working = working - m.key
            refresh()
        }
    }

    fun unloadAll() {
        viewModelScope.launch {
            runCatching { models.flatMap { it.loaded }.forEach { api().unload(it.id) } }.onFailure { error = friendlyError(it) }
            refresh()
        }
    }

    /** [model]: an LM Studio catalog name (e.g. qwen/qwen3-14b) or a Hugging Face link. */
    fun download(model: String, quantization: String?) {
        if (model.isBlank()) return
        viewModelScope.launch {
            runCatching { api().download(model.trim(), quantization?.trim()) }
                .onSuccess { job -> downloads.add(0, job); pollDownloads() }
                .onFailure { error = "Download didn't start: ${friendlyError(it)}" }
        }
    }

    private fun pollDownloads() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive && downloads.any { !it.done && it.status != "failed" }) {
                delay(1500)
                for (i in downloads.indices) {
                    val j = downloads[i]
                    if (j.done || j.status == "failed") continue
                    runCatching { api().downloadStatus(j) }.onSuccess { downloads[i] = it }
                }
                if (downloads.any { it.status == "completed" }) refresh()
            }
        }
    }

    fun clearFinishedDownloads() { downloads.removeAll { it.done || it.status == "failed" } }
}
