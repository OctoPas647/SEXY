package com.octopas.sexy

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octopas.sexy.data.AiClient
import com.octopas.sexy.data.Attachment
import com.octopas.sexy.data.BridgeClient
import com.octopas.sexy.data.ChatDoc
import com.octopas.sexy.data.ChatMessage
import com.octopas.sexy.data.ChatMsg
import com.octopas.sexy.data.ChatSummary
import com.octopas.sexy.data.FileExtract
import com.octopas.sexy.data.GenStats
import com.octopas.sexy.data.LmStudioApi
import com.octopas.sexy.data.LmsFormat
import com.octopas.sexy.data.MsgVersion
import com.octopas.sexy.data.NativeChatRequest
import com.octopas.sexy.data.NativeEvent
import com.octopas.sexy.data.Sampling
import com.octopas.sexy.data.ServerKind
import com.octopas.sexy.data.StreamEvent
import com.octopas.sexy.data.SyncState
import com.octopas.sexy.data.ToolUse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file being attached to the next message (uploading to the PC, then ready). */
data class PendingAttachment(val attachment: Attachment, val uploading: Boolean = false, val error: String? = null)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val sexy = app as SexyApp
    private val settings = sexy.settings
    private val repo = sexy.chats

    // ---------- chat list ----------
    var chats by mutableStateOf<List<ChatSummary>>(emptyList())
        internal set
    var listLoading by mutableStateOf(false)
        private set
    val syncState get() = repo.state

    // ---------- open chat ----------
    var doc by mutableStateOf(LmsFormat.empty(systemPrompt = settings.systemPrompt, plugins = settings.defaultPlugins))
        internal set
    val messages = mutableStateListOf<ChatMessage>()
    var isOpen by mutableStateOf(false)
        internal set
    var opening by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    /** What's happening right now: "Loading model 40%", "Reading your message", "Searching with duckduckgo"... */
    var phase by mutableStateOf<String?>(null)
        private set
    var errorBanner by mutableStateOf<String?>(null)
    var sampling by mutableStateOf(Sampling())
        private set
    val pending = mutableStateListOf<PendingAttachment>()

    var thinking by mutableStateOf(settings.thinking)
        private set

    /** Set by the UI: called when a message couldn't reach the PC, so the app looks for it again. */
    var onConnectionProblem: (() -> Unit)? = null
    /** Set by the UI: which model to use and whether the server is LM Studio. */
    var modelProvider: () -> String = { settings.modelId ?: "default" }

    private var job: Job? = null
    private var watcher: Job? = null
    /** LM Studio keeps tool chats on its side; continuing from the last answer avoids resending history. */
    private var lastResponse: Triple<String, Int, String>? = null

    init {
        refreshList()
        viewModelScope.launch {
            // Keep the list fresh (chats started or renamed in LM Studio on the PC show up here).
            while (isActive) {
                delay(15_000)
                if (!busy) refreshList(quiet = true)
            }
        }
    }

    fun refreshList(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) listLoading = true
            runCatching { repo.list() }.onSuccess { list ->
                // PC unreachable and no phone copy: keep showing the chats we already had instead of an empty list.
                val offline = repo.state.value == SyncState.PC_OFFLINE || repo.state.value == SyncState.NO_BRIDGE
                if (!(offline && list.isEmpty() && chats.isNotEmpty())) chats = list
            }
            listLoading = false
        }
    }

    fun syncNow() {
        viewModelScope.launch { repo.syncNow(); refreshList() }
    }

    fun updateThinking(on: Boolean) {
        thinking = on
        settings.thinking = on
    }

    // ---------- open / new / close ----------

    fun newChat() {
        if (busy) stop()
        doc = LmsFormat.empty(systemPrompt = settings.systemPrompt, plugins = settings.defaultPlugins)
        messages.clear()
        pending.clear()
        sampling = Sampling()
        errorBanner = null
        isOpen = true
        watch()
    }

    fun open(id: String) {
        if (busy) stop()
        opening = true
        isOpen = true
        messages.clear()
        pending.clear()
        errorBanner = null
        viewModelScope.launch {
            try {
                val d = repo.open(id)
                doc = d
                messages.addAll(d.messages)
                sampling = settings.chatSampling(d.id)
                watch()
            } catch (e: Exception) {
                errorBanner = e.message ?: "Couldn't open the chat"
            } finally {
                opening = false
            }
        }
    }

    fun close() {
        watcher?.cancel()
        isOpen = false
        refreshList(quiet = true)
    }

    /** While a chat is open, pick up changes made to it in LM Studio on the PC. */
    private fun watch() {
        watcher?.cancel()
        watcher = viewModelScope.launch {
            while (isActive) {
                delay(8_000)
                if (busy || doc.messages.isEmpty() && messages.isEmpty()) continue
                val fresh = runCatching { repo.refreshIfChanged(doc) }.getOrNull() ?: continue
                if (!busy) {
                    doc = fresh
                    messages.clear()
                    messages.addAll(fresh.messages)
                }
            }
        }
    }

    // ---------- chat settings ----------

    fun setSystemPrompt(p: String) { doc = doc.copy(systemPrompt = p); saveSoon() }
    fun rename(name: String) { doc = doc.copy(name = name.ifBlank { null }); saveSoon() }
    fun togglePin() { doc = doc.copy(pinned = !doc.pinned); saveSoon() }

    fun setPlugins(ids: List<String>) {
        doc = doc.copy(plugins = ids)
        saveSoon()
    }

    fun updateSampling(s: Sampling) {
        sampling = s
        settings.setChatSampling(doc.id, s)
    }

    fun renameChat(id: String, name: String) = editSaved(id) { it.copy(name = name.ifBlank { null }) }
    fun pinChat(id: String, pinned: Boolean) = editSaved(id) { it.copy(pinned = pinned) }

    private fun editSaved(id: String, change: (ChatDoc) -> ChatDoc) {
        viewModelScope.launch {
            runCatching {
                val d = if (doc.id == id) doc else repo.open(id)
                val saved = repo.save(change(d))
                if (doc.id == id) doc = saved
            }.onFailure { errorBanner = it.message }
            refreshList(quiet = true)
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            if (doc.id == id) newChat().also { isOpen = false }
            refreshList(quiet = true)
        }
    }

    private fun saveSoon() {
        if (messages.isEmpty()) return
        viewModelScope.launch { persist() }
    }

    private suspend fun persist() {
        if (messages.isEmpty()) return
        val toSave = doc.copy(
            messages = messages.toList(),
            name = doc.name ?: messages.firstOrNull { it.role == "user" }?.current?.text?.lineSequence()?.firstOrNull()?.take(48),
            lastModel = modelProvider().takeIf { it != "default" } ?: doc.lastModel,
        )
        val saved = repo.save(toSave)
        doc = saved.copy(messages = messages.toList())
        if (saved.id != toSave.id) {
            errorBanner = "This chat was also changed in LM Studio on the PC, so your version was saved as a new chat."
        }
        refreshList(quiet = true)
    }

    // ---------- attachments ----------

    fun attach(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { runCatching { FileExtract.read(ctx, uri) }.getOrNull() } ?: return@launch
            val placeholder = PendingAttachment(Attachment(picked.name, picked.isImage), uploading = true)
            pending += placeholder
            val ready = withContext(Dispatchers.IO) {
                val pcName = bridge()?.let { b -> runCatching { b.upload(picked.name, picked.bytes).name }.getOrNull() }
                if (picked.isImage) {
                    Attachment(picked.name, true, pcName, dataUrl = FileExtract.imageDataUrl(ctx, picked))
                } else {
                    Attachment(picked.name, false, pcName, extractedText = FileExtract.text(ctx, picked))
                }
            }
            val i = pending.indexOf(placeholder)
            val note = when {
                ready.pcName == null -> "Couldn't save it on the PC (bridge offline) - it's only used for this message."
                !ready.isImage && ready.extractedText == null -> "Saved on the PC. The model only sees its name (this kind of file can't be read)."
                else -> null
            }
            if (i >= 0) pending[i] = PendingAttachment(ready, uploading = false, error = note)
        }
    }

    fun removeAttachment(p: PendingAttachment) { pending.remove(p) }

    private fun bridge(): BridgeClient? = (settings.bridgeUrl ?: BridgeClient.urlFor(settings.serverUrl))?.let { BridgeClient(it) }

    // ---------- sending ----------

    fun send(text: String) {
        val prompt = text.trim()
        val atts = pending.filter { !it.uploading }.map { it.attachment }
        if ((prompt.isEmpty() && atts.isEmpty()) || busy || pending.any { it.uploading }) return
        val files = atts.filter { !it.isImage && it.extractedText != null }
        val pre = if (files.isEmpty()) null else buildString {
            append(prompt)
            files.forEach { append("\n\n").append(FileExtract.forPrompt(it.name, it.extractedText!!)) }
        }
        val names = atts.filter { !it.isImage && it.extractedText == null }
        val preWithNames = if (names.isEmpty()) pre else (pre ?: prompt) + names.joinToString("") { "\n\n[Attached file saved on the PC: ${it.pcName ?: it.name}]" }
        pending.clear()
        messages += ChatMessage(listOf(MsgVersion("user", prompt, attachments = atts, preprocessed = preWithNames)))
        messages += ChatMessage(listOf(MsgVersion("assistant", "")))
        generate(messages.lastIndex)
    }

    /** New answer for the assistant message at [index] (kept as a new version, like LM Studio). */
    fun regenerate(index: Int) {
        if (busy || index !in messages.indices || messages[index].role != "assistant") return
        while (messages.lastIndex > index) messages.removeAt(messages.lastIndex)
        val m = messages[index]
        messages[index] = ChatMessage(m.versions + MsgVersion("assistant", ""), m.versions.size)
        generate(index)
    }

    /** Edit a message you sent: the new text becomes a new version and the answer is redone. */
    fun editAndResend(index: Int, newText: String) {
        if (busy || index !in messages.indices) return
        val m = messages[index]
        if (m.role != "user") {
            messages[index] = ChatMessage(m.versions + m.current.copy(text = newText, raw = null), m.versions.size)
            saveSoon()
            return
        }
        while (messages.lastIndex > index) messages.removeAt(messages.lastIndex)
        val old = m.current
        val pre = old.preprocessed?.let { p -> if (p.startsWith(old.text)) newText + p.removePrefix(old.text) else p }
        messages[index] = ChatMessage(m.versions + old.copy(text = newText, preprocessed = pre, raw = null), m.versions.size)
        messages += ChatMessage(listOf(MsgVersion("assistant", "")))
        generate(messages.lastIndex)
    }

    fun deleteMessage(index: Int) {
        if (busy || index !in messages.indices) return
        messages.removeAt(index)
        saveSoon()
    }

    fun selectVersion(index: Int, version: Int) {
        if (busy || index !in messages.indices) return
        val m = messages[index]
        messages[index] = m.copy(selected = version.coerceIn(0, m.versions.lastIndex))
        saveSoon()
    }

    fun stop() {
        job?.cancel()
    }

    private fun updateCurrent(index: Int, f: (MsgVersion) -> MsgVersion) {
        if (index !in messages.indices) return
        val m = messages[index]
        val v = m.versions.toMutableList()
        v[m.selected] = f(v[m.selected])
        messages[index] = m.copy(versions = v)
    }

    private fun generate(index: Int) {
        val model = modelProvider()
        busy = true
        errorBanner = null
        phase = "Sending…"
        val s = sampling.over(settings.defaultSampling)
        val history = messages.subList(0, index).map { it.current }
        job = viewModelScope.launch {
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val tools = mutableListOf<ToolUse>()
            var stats: GenStats? = null
            val started = System.nanoTime()
            var firstToken: Long? = null
            var chunks = 0
            var lastPush = 0L
            fun push(force: Boolean = false) {
                val now = System.nanoTime()
                if (!force && now - lastPush < 50_000_000) return
                lastPush = now
                updateCurrent(index) { it.copy(text = text.toString(), reasoning = reasoning.toString(), tools = tools.toList(), model = model) }
            }
            try {
                val useTools = settings.serverKind == ServerKind.LM_STUDIO && doc.plugins.isNotEmpty()
                if (useTools) {
                    val last = history.lastOrNull()
                    val chain = lastResponse?.takeIf { it.first == doc.id && it.second == index - 1 }?.third
                    val earlier = history.dropLast(1)
                    val system = buildString {
                        append(doc.systemPrompt)
                        if (chain == null && earlier.isNotEmpty()) {
                            if (isNotBlank()) append("\n\n")
                            append("Conversation so far:\n")
                            earlier.forEach { v -> append(if (v.role == "user") "User: " else "Assistant: ").append(v.contextText).append("\n\n") }
                        }
                    }
                    api().chat(
                        NativeChatRequest(
                            model = model,
                            text = last?.contextText.orEmpty(),
                            images = last?.attachments?.mapNotNull { it.dataUrl }.orEmpty(),
                            systemPrompt = system.takeIf { it.isNotBlank() && chain == null },
                            plugins = doc.plugins,
                            sampling = s,
                            previousResponseId = chain,
                        )
                    ).collect { ev ->
                        when (ev) {
                            is NativeEvent.ModelLoad -> phase = "Loading model ${(ev.progress * 100).toInt()}%"
                            is NativeEvent.PromptProgress -> phase = "Reading the conversation ${(ev.progress * 100).toInt()}%"
                            is NativeEvent.Reasoning -> { phase = "Thinking…"; reasoning.append(ev.text); if (firstToken == null) firstToken = System.nanoTime(); push() }
                            is NativeEvent.Message -> { phase = null; text.append(ev.text); if (firstToken == null) firstToken = System.nanoTime(); chunks++; push() }
                            is NativeEvent.ToolStart -> { phase = "Using ${ev.provider ?: ev.tool}…"; tools += ToolUse("${ev.provider ?: "tool"}: ${ev.tool}", "running"); push(true) }
                            is NativeEvent.ToolDone -> {
                                val i = tools.indexOfLast { it.status == "running" }
                                val t = ToolUse("${ev.provider ?: "tool"}: ${ev.tool} ${ev.arguments.take(80)}", "toolCallSucceeded", true, ev.output.take(4000))
                                if (i >= 0) tools[i] = t else tools += t
                                push(true)
                            }
                            is NativeEvent.ToolFailed -> { tools += ToolUse(ev.reason, "toolCallFailed", ok = false); push(true) }
                            is NativeEvent.End -> {
                                stats = ev.stats
                                ev.responseId?.let { lastResponse = Triple(doc.id, index + 1, it) }
                            }
                        }
                    }
                } else {
                    ensureLoaded(model)
                    val system = doc.systemPrompt.takeIf { it.isNotBlank() }?.let { listOf(ChatMsg("system", it)) }.orEmpty()
                    val msgs = system + history.filter { it.contextText.isNotBlank() || it.attachments.any { a -> a.dataUrl != null } }.map { v ->
                        ChatMsg(v.role, v.contextText, v.attachments.mapNotNull { it.dataUrl })
                    }
                    phase = "Reading the conversation…"
                    client().streamChat(model, msgs, thinking, sampling = s).collect { ev ->
                        when (ev) {
                            is StreamEvent.Content -> { phase = null; text.append(ev.text); if (firstToken == null) firstToken = System.nanoTime(); chunks++; push() }
                            is StreamEvent.Reasoning -> { phase = "Thinking…"; reasoning.append(ev.text); if (firstToken == null) firstToken = System.nanoTime(); push() }
                            is StreamEvent.Stats -> stats = GenStats(
                                tokensPerSecond = ev.tokensPerSecond, predictedTokens = ev.completionTokens, promptTokens = ev.promptTokens,
                            )
                        }
                    }
                }
                val ft = firstToken
                stats = (stats ?: GenStats()).copy(
                    timeToFirstTokenSec = stats?.timeToFirstTokenSec ?: ft?.let { (it - started) / 1e9 },
                    totalTimeSec = (System.nanoTime() - started) / 1e9,
                    stopReason = "eosFound",
                )
                push(true)
                updateCurrent(index) { it.copy(stats = stats) }
            } catch (e: Throwable) {
                if (e is CancellationException || !isActive) {
                    push(true)
                    updateCurrent(index) { it.copy(stats = (stats ?: GenStats()).copy(stopReason = "userStopped")) }
                } else {
                    push(true)
                    errorBanner = friendlyError(e)
                    if (isConnectionProblem(e)) onConnectionProblem?.invoke()
                    // An empty failed answer isn't worth keeping.
                    if (text.isEmpty() && reasoning.isEmpty() && index in messages.indices) {
                        val m = messages[index]
                        if (m.versions.size > 1) messages[index] = ChatMessage(m.versions.dropLast(1), m.versions.size - 2)
                        else messages.removeAt(index)
                    }
                }
            } finally {
                busy = false
                phase = null
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { persist() } }
            }
        }
    }

    /** Loads the model with the settings picked on the phone (LM Studio only) before chatting. */
    private suspend fun ensureLoaded(model: String) {
        if (settings.serverKind != ServerKind.LM_STUDIO) return
        val cfg = settings.loadConfig(model) ?: return
        val api = api()
        val all = runCatching { api.models() }.getOrNull() ?: return
        val m = all.firstOrNull { it.key == model } ?: return
        if (m.isLoaded) return
        phase = "Loading ${m.displayName}…"
        if (settings.unloadOthersOnLoad) all.flatMap { it.loaded }.forEach { runCatching { api.unload(it.id) } }
        api.load(model, cfg)
    }

    private fun client() = AiClient(settings.serverUrl ?: "", settings.apiKey, kind = settings.serverKind)
    private fun api() = LmStudioApi(settings.serverUrl ?: "", settings.apiKey)

    /** Transcript as Markdown, for sharing. */
    fun transcript(): String = buildString {
        append("# ").append(doc.title).append("\n\n")
        messages.forEach { m ->
            append(if (m.role == "user") "**You**\n\n" else "**${m.current.model ?: "Assistant"}**\n\n")
            append(m.current.text).append("\n\n")
        }
    }
}

fun formatStats(s: StreamEvent.Stats): String? = GenStats(s.tokensPerSecond, null, s.completionTokens).label()
