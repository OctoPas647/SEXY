package com.octopas.sexy.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Where the chat list came from, for the little status under "Chats". */
enum class SyncState {
    SYNCED, SYNCING, PC_OFFLINE, NO_BRIDGE, PENDING,
    /** The server isn't LM Studio (e.g. llama.cpp on the laptop): there's no chat folder to sync with, chats stay on the phone. */
    ON_PHONE,
}

/**
 * Chats live on the PC, in LM Studio's own chat folder (through the bridge), so the phone and
 * LM Studio desktop see the same chats. The phone keeps a copy only when [AppSettings.keepLocalCopy] is on.
 *
 * Saving: every finished answer is written to the PC. If the PC changed the same chat meanwhile, the two
 * are merged (or, if both sides added different messages, the phone's version is kept as a separate chat).
 * If the PC can't be reached, the chat waits in memory (and on the phone if the copy is on) and is sent later.
 */
class ChatRepository(context: Context, private val settings: AppSettings) {
    private val localDir = File(context.filesDir, "chats-lmstudio")
    private val legacyDir = File(context.filesDir, "chats")
    private val mutex = Mutex()

    /** Chats saved on the phone that haven't reached the PC yet (id -> doc). */
    private val outbox = LinkedHashMap<String, ChatDoc>()

    private val _state = MutableStateFlow(SyncState.SYNCED)
    val state: StateFlow<SyncState> = _state

    val pendingCount: Int get() = outbox.size

    /**
     * The bridge keeps chats in LM Studio's chat folder, so it only exists next to LM Studio. With any
     * other server (llama.cpp on the laptop, Ollama, ...) and no bridge address set, chats are kept on the phone.
     */
    private fun phoneOnly(): Boolean {
        val kind = settings.serverKind
        return settings.bridgeUrl == null && kind != null && kind != ServerKind.LM_STUDIO
    }

    private fun keepLocal(): Boolean = settings.keepLocalCopy || phoneOnly()

    private fun bridge(): BridgeClient? =
        if (phoneOnly()) null
        else (settings.bridgeUrl ?: BridgeClient.urlFor(settings.serverUrl))?.let { BridgeClient(it) }

    // ---------- list / open ----------

    suspend fun list(): List<ChatSummary> = withContext(Dispatchers.IO) {
        val b = bridge()
        if (phoneOnly()) _state.value = SyncState.ON_PHONE
        val remote = if (b == null) null else runCatching { b.list() }
            .onFailure { _state.value = if (it is BridgeException && it.code == 404) SyncState.NO_BRIDGE else SyncState.PC_OFFLINE }
            .getOrNull()
        if (remote != null) {
            flushOutbox(b!!)
            _state.value = if (outbox.isEmpty()) SyncState.SYNCED else SyncState.PENDING
        }
        val local = if (keepLocal()) localSummaries() else emptyList()
        val pending = outbox.values.map { LmsFormat.summary(it) }
        merge(remote ?: emptyList(), local, pending)
    }

    private fun merge(vararg lists: List<ChatSummary>): List<ChatSummary> {
        val byId = LinkedHashMap<String, ChatSummary>()
        for (l in lists) for (s in l) {
            val old = byId[s.id]
            if (old == null || s.updatedAt > old.updatedAt) byId[s.id] = s
        }
        return byId.values.sortedWith(compareByDescending<ChatSummary> { it.pinned }.thenByDescending { maxOf(it.updatedAt, it.createdAt) })
    }

    suspend fun open(id: String): ChatDoc = withContext(Dispatchers.IO) {
        outbox[id]?.let { return@withContext it }
        val b = bridge()
        val remote = b?.let { runCatching { it.get(id) }.getOrNull() }
        if (remote != null) {
            if (settings.keepLocalCopy) writeLocal(remote)
            return@withContext remote
        }
        readLocal(id) ?: throw BridgeException(
            if (keepLocal()) "This chat isn't on the phone and the PC can't be reached."
            else "Can't reach your PC. Chats are only kept on the PC - turn on \"Keep a copy on this phone\" to read them offline."
        )
    }

    /** Fresh copy from the PC if it changed there (null if not newer or PC offline). */
    suspend fun refreshIfChanged(doc: ChatDoc): ChatDoc? = withContext(Dispatchers.IO) {
        if (outbox.containsKey(doc.id)) return@withContext null
        val b = bridge() ?: return@withContext null
        val summary = runCatching { b.list() }.getOrNull()?.firstOrNull { it.id == doc.id } ?: return@withContext null
        if (summary.updatedAt <= doc.updatedAt + 1000) return@withContext null
        runCatching { b.get(doc.id) }.getOrNull()
    }

    // ---------- save / delete ----------

    /** Saves to the PC (merging if needed). Returns the doc as stored (new updatedAt, maybe a new id). */
    suspend fun save(doc: ChatDoc): ChatDoc = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (keepLocal()) writeLocal(doc)
            val b = bridge()
            if (b == null && phoneOnly()) {
                _state.value = SyncState.ON_PHONE
                return@withContext doc
            }
            if (b == null) {
                outbox[doc.id] = doc
                _state.value = SyncState.NO_BRIDGE
                return@withContext doc
            }
            try {
                val saved = putMerged(b, doc)
                outbox.remove(doc.id)
                if (settings.keepLocalCopy) writeLocal(saved)
                _state.value = if (outbox.isEmpty()) SyncState.SYNCED else SyncState.PENDING
                saved
            } catch (e: Exception) {
                outbox[doc.id] = doc
                _state.value = SyncState.PC_OFFLINE
                doc
            }
        }
    }

    private suspend fun putMerged(b: BridgeClient, doc: ChatDoc): ChatDoc {
        return try {
            val t = b.put(doc.id, LmsFormat.encode(doc), doc.updatedAt.takeIf { it > 0 })
            doc.copy(updatedAt = t)
        } catch (c: ChatConflict) {
            val pc = b.get(doc.id)
            val merged = ChatMerge.merge(phone = doc, pc = pc)
            if (merged.id != pc.id) {
                // Both sides added different messages: keep the PC's chat, save the phone's as a copy.
                val t = b.put(merged.id, LmsFormat.encode(merged), null)
                merged.copy(updatedAt = t)
            } else {
                val t = b.put(merged.id, LmsFormat.encode(merged), pc.updatedAt)
                merged.copy(updatedAt = t)
            }
        }
    }

    private suspend fun flushOutbox(b: BridgeClient) = mutex.withLock {
        val it = outbox.values.toList()
        for (doc in it) {
            runCatching { putMerged(b, doc) }.onSuccess { saved ->
                outbox.remove(doc.id)
                if (settings.keepLocalCopy) writeLocal(saved)
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        outbox.remove(id)
        File(localDir, fileName(id)).delete()
        bridge()?.let { b -> runCatching { b.delete(id) } }
        Unit
    }

    /** Sends everything waiting to the PC now. */
    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        val b = bridge() ?: return@withContext false
        if (!b.health()) { _state.value = SyncState.PC_OFFLINE; return@withContext false }
        flushOutbox(b)
        migrateLegacy(b)
        _state.value = if (outbox.isEmpty()) SyncState.SYNCED else SyncState.PENDING
        true
    }

    // ---------- phone copy ----------

    private fun fileName(id: String) = id.replace("/", "__") + ".conversation.json"

    private fun writeLocal(doc: ChatDoc) {
        localDir.mkdirs()
        val tmp = File(localDir, fileName(doc.id) + ".tmp")
        tmp.writeText(LmsFormat.encode(doc))
        tmp.renameTo(File(localDir, fileName(doc.id)))
        File(localDir, fileName(doc.id) + ".t").writeText(doc.updatedAt.toString())
    }

    private fun readLocal(id: String): ChatDoc? {
        val f = File(localDir, fileName(id))
        if (!f.exists()) return null
        val t = File(localDir, fileName(id) + ".t").takeIf { it.exists() }?.readText()?.toLongOrNull() ?: f.lastModified()
        return runCatching { LmsFormat.parse(id, f.readText(), t) }.getOrNull()
    }

    private fun localSummaries(): List<ChatSummary> = localDir.listFiles { f -> f.name.endsWith(".conversation.json") }.orEmpty()
        .mapNotNull { f -> readLocal(f.name.removeSuffix(".conversation.json").replace("__", "/"))?.let { LmsFormat.summary(it) } }

    /** Turning the phone copy off removes it (the PC keeps everything). Not while the phone is the only place chats live. */
    fun deleteLocalCopy() {
        if (phoneOnly()) return
        localDir.listFiles().orEmpty().forEach { it.delete() }
    }

    /** Fills the phone copy with every chat on the PC. */
    suspend fun downloadAll(): Int = withContext(Dispatchers.IO) {
        val b = bridge() ?: return@withContext 0
        var n = 0
        for (s in b.list()) runCatching { writeLocal(b.get(s.id)) }.onSuccess { n++ }
        n
    }

    // ---------- chats from older SEXY versions (stored on the phone) -> PC ----------

    @kotlinx.serialization.Serializable
    private data class LegacyMsg(val role: String, val content: String, val reasoning: String = "", val error: String? = null)

    @kotlinx.serialization.Serializable
    private data class LegacyChat(val id: String, val title: String, val updatedAt: Long, val messages: List<LegacyMsg>)

    private suspend fun migrateLegacy(b: BridgeClient) {
        val files = legacyDir.listFiles { f -> f.extension == "json" }.orEmpty()
        for (f in files) {
            val old = runCatching { appJson.decodeFromString(LegacyChat.serializer(), f.readText()) }.getOrNull() ?: continue
            val doc = LmsFormat.empty(LmsFormat.newId(old.updatedAt)).copy(
                name = old.title,
                messages = old.messages.filter { it.error == null && it.content.isNotBlank() }
                    .map { ChatMessage(listOf(MsgVersion(it.role, it.content, it.reasoning))) },
            )
            if (runCatching { b.put(doc.id, LmsFormat.encode(doc), null) }.isSuccess) f.delete()
        }
    }

    fun hasLegacyChats() = legacyDir.listFiles { f -> f.extension == "json" }.orEmpty().isNotEmpty()
}

/** Merging a chat changed on both the phone and the PC. Pure, so it's unit-tested. */
object ChatMerge {
    private fun sig(m: ChatMessage) = m.role + "\u0000" + m.current.text

    fun merge(phone: ChatDoc, pc: ChatDoc): ChatDoc {
        val p = phone.messages.map(::sig)
        val c = pc.messages.map(::sig)
        // Names/pins changed on the PC are kept unless the phone changed them.
        val meta = pc.copy(
            messages = phone.messages,
            name = phone.name ?: pc.name,
            pinned = phone.pinned || pc.pinned,
            systemPrompt = phone.systemPrompt.ifBlank { pc.systemPrompt },
            plugins = phone.plugins,
            lastModel = phone.lastModel ?: pc.lastModel,
        )
        return when {
            // The phone continued what's on the PC: take the phone's messages, the PC's other fields.
            p.size >= c.size && p.take(c.size) == c -> meta
            // The PC moved further and the phone added nothing new: the PC wins.
            c.size >= p.size && c.take(p.size) == p -> pc
            // Both added different things: keep both chats.
            else -> phone.copy(
                id = LmsFormat.newId(),
                name = (phone.title) + " (phone)",
                raw = kotlinx.serialization.json.JsonObject(emptyMap()),
                updatedAt = 0,
            )
        }
    }
}
