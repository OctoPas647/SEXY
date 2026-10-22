package com.octopas.sexy

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octopas.sexy.data.AiClient
import com.octopas.sexy.data.ChatMsg
import com.octopas.sexy.data.Conversation
import com.octopas.sexy.data.StoredMessage
import com.octopas.sexy.data.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class UiMessage(
    val role: String,
    content: String = "",
    reasoning: String = "",
    stats: String? = null,
    error: String? = null,
) {
    val key: Long = ids.incrementAndGet()
    var content by mutableStateOf(content)
    var reasoning by mutableStateOf(reasoning)
    var stats by mutableStateOf(stats)
    var error by mutableStateOf(error)
    var streaming by mutableStateOf(false)

    private companion object { val ids = AtomicLong() }
}

fun formatStats(s: StreamEvent.Stats): String? {
    val parts = buildList {
        s.tokensPerSecond?.let { add("%.1f tok/s".format(it)) }
        s.completionTokens?.let { add("$it tokens") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val sexy = app as SexyApp
    private val settings = sexy.settings

    val messages = mutableStateListOf<UiMessage>()
    var conversations by mutableStateOf(sexy.chats.list())
        private set
    var busy by mutableStateOf(false)
        private set
    var thinking by mutableStateOf(settings.thinking)
        private set

    private var currentId = UUID.randomUUID().toString()
    private var job: Job? = null

    fun updateThinking(on: Boolean) {
        thinking = on
        settings.thinking = on
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || busy) return
        val history = buildList {
            settings.systemPrompt.takeIf { it.isNotBlank() }?.let { add(ChatMsg("system", it)) }
            messages.filter { it.error == null && it.content.isNotBlank() }.forEach { add(ChatMsg(it.role, it.content)) }
            add(ChatMsg("user", prompt))
        }
        messages += UiMessage("user", prompt)
        val reply = UiMessage("assistant").apply { streaming = true }
        messages += reply
        busy = true
        val client = AiClient(settings.serverUrl ?: "", settings.apiKey)
        val model = settings.modelId ?: "default"
        job = viewModelScope.launch {
            try {
                client.streamChat(model, history, thinking).collect { ev ->
                    when (ev) {
                        is StreamEvent.Content -> reply.content += ev.text
                        is StreamEvent.Reasoning -> reply.reasoning += ev.text
                        is StreamEvent.Stats -> formatStats(ev)?.let { reply.stats = it }
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException || !isActive) {
                    reply.stats = listOfNotNull(reply.stats, "stopped").joinToString(" · ")
                } else {
                    reply.error = friendlyError(e)
                }
            } finally {
                reply.streaming = false
                busy = false
                save()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun newChat() {
        if (busy) { stop(); save() }
        messages.clear()
        currentId = UUID.randomUUID().toString()
    }

    fun open(c: Conversation) {
        if (busy) { stop(); save() }
        messages.clear()
        c.messages.forEach { m -> messages += UiMessage(m.role, m.content, m.reasoning, m.stats, m.error) }
        currentId = c.id
    }

    fun delete(c: Conversation) {
        sexy.chats.delete(c.id)
        if (c.id == currentId) newChat()
        conversations = sexy.chats.list()
    }

    fun deleteAll() {
        newChat()
        sexy.chats.deleteAll()
        conversations = emptyList()
    }

    private fun save() {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == "user" }?.content?.lineSequence()?.first()?.take(60) ?: "Chat"
        val conv = Conversation(
            id = currentId,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = messages.map { StoredMessage(it.role, it.content, it.reasoning, it.stats, it.error) },
        )
        runCatching { sexy.chats.save(conv) }
        conversations = sexy.chats.list()
    }
}
