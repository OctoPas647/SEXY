package com.octopas.sexy.data

import android.content.Context
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class StoredMessage(
    val role: String,
    val content: String,
    val reasoning: String = "",
    val stats: String? = null,
    val error: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
)

/** Chat history as small JSON files in the app's private storage. */
class ChatStore(context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }

    fun list(): List<Conversation> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { appJson.decodeFromString<Conversation>(it.readText()) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun save(c: Conversation) {
        val tmp = File(dir, "${c.id}.tmp")
        tmp.writeText(appJson.encodeToString(Conversation.serializer(), c))
        tmp.renameTo(File(dir, "${c.id}.json"))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    fun deleteAll() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }
}
