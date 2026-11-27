package com.octopas.sexy.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

/** Thinking levels LM Studio offers. AUTO = the model's own default. */
enum class Reasoning(val api: String, val label: String) {
    AUTO("auto", "Auto"), OFF("off", "Off"), LOW("low", "Low"), MEDIUM("medium", "Medium"), HIGH("high", "High"), ON("on", "On");

    companion object {
        fun from(s: String?) = entries.firstOrNull { it.api == s } ?: AUTO
    }
}

/** Sampling settings, like LM Studio's right-hand panel. null = the model's default. */
@Serializable
data class Sampling(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val repeatPenalty: Double? = null,
    val maxTokens: Int? = null,
    val seed: Int? = null,
    val contextLength: Int? = null,
    val reasoning: Reasoning = Reasoning.AUTO,
) {
    /** Per-chat values win over the defaults. */
    fun over(defaults: Sampling) = Sampling(
        temperature ?: defaults.temperature, topP ?: defaults.topP, topK ?: defaults.topK, minP ?: defaults.minP,
        repeatPenalty ?: defaults.repeatPenalty, maxTokens ?: defaults.maxTokens, seed ?: defaults.seed,
        contextLength ?: defaults.contextLength, if (reasoning != Reasoning.AUTO) reasoning else defaults.reasoning,
    )
}

data class GenStats(
    val tokensPerSecond: Double? = null,
    val timeToFirstTokenSec: Double? = null,
    val predictedTokens: Int? = null,
    val promptTokens: Int? = null,
    val stopReason: String? = null,
    val totalTimeSec: Double? = null,
) {
    fun label(): String? = buildList {
        tokensPerSecond?.let { add("%.1f tok/s".format(it)) }
        predictedTokens?.let { add("$it tokens") }
        timeToFirstTokenSec?.let { add("%.2fs to first token".format(it)) }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** A tool the model used (an LM Studio plugin or MCP server). */
data class ToolUse(val name: String, val status: String, val ok: Boolean = true, val output: String? = null)

/** A picture or file attached to a message. The file itself is kept on the PC ([pcName]). */
data class Attachment(
    val name: String,
    val isImage: Boolean,
    val pcName: String? = null,
    /** Images only, while this chat is open: what is sent to the model. */
    val dataUrl: String? = null,
    /** Files only: the text read out of it (goes to the model, saved as LM Studio's "preprocessed" text). */
    val extractedText: String? = null,
)

/** One version of a message (LM Studio keeps every regenerated answer as a version). */
data class MsgVersion(
    val role: String,
    val text: String,
    val reasoning: String = "",
    val tools: List<ToolUse> = emptyList(),
    val stats: GenStats? = null,
    val model: String? = null,
    val attachments: List<Attachment> = emptyList(),
    /** What the model really saw for this user message (text + file contents). */
    val preprocessed: String? = null,
    /** The original JSON, kept untouched so saving never damages chats made in LM Studio. */
    val raw: JsonObject? = null,
) {
    /** Text the model gets as context for this message. */
    val contextText: String get() = if (role == "user") preprocessed ?: text else text
}

data class ChatMessage(val versions: List<MsgVersion>, val selected: Int = versions.lastIndex) {
    val current: MsgVersion get() = versions[selected.coerceIn(0, versions.lastIndex)]
    val role get() = current.role
}

/** A chat, as stored by LM Studio in .lmstudio/conversations/<id>.conversation.json. */
data class ChatDoc(
    val id: String,
    val name: String?,
    val pinned: Boolean,
    val createdAt: Long,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val plugins: List<String>,
    val lastModel: String?,
    val tokenCount: Long = 0,
    /** When the PC last changed this file (for merging edits made on both sides). */
    val updatedAt: Long = 0,
    /** The whole original file, so every LM Studio field we don't use is written back as it was. */
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val title: String get() = name?.takeIf { it.isNotBlank() }
        ?: messages.firstOrNull { it.role == "user" }?.current?.text?.lineSequence()?.firstOrNull()?.take(48)
        ?: "New chat"
    val folder: String get() = id.substringBeforeLast('/', "")
}

/** List entry from the PC (cheap: no messages). */
@Serializable
data class ChatSummary(
    val id: String,
    val folder: String = "",
    val name: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val tokenCount: Long = 0,
    val model: String? = null,
) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: "New chat"
}

/** Reads and writes LM Studio's chat files. Pure Kotlin, so it is unit-tested. */
object LmsFormat {
    private const val ATTACH_MARK = "📎 " // 📎

    fun newId(now: Long = System.currentTimeMillis()) = now.toString()

    fun empty(id: String = newId(), systemPrompt: String = "", plugins: List<String> = emptyList()) = ChatDoc(
        id = id, name = null, pinned = false, createdAt = id.substringAfterLast('/').toLongOrNull() ?: System.currentTimeMillis(),
        systemPrompt = systemPrompt, messages = emptyList(), plugins = plugins, lastModel = null,
    )

    fun summary(d: ChatDoc) = ChatSummary(d.id, d.folder, d.title, d.pinned, d.createdAt, d.updatedAt, d.tokenCount, d.lastModel)

    fun parse(id: String, text: String, updatedAt: Long = 0): ChatDoc {
        val o = appJson.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("not a chat")
        val messages = (o["messages"] as? JsonArray).orEmpty().mapNotNull { parseMessage(it as? JsonObject) }
        return ChatDoc(
            id = id,
            name = o.str("name"),
            pinned = o.bool("pinned") == true,
            createdAt = (o["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0,
            systemPrompt = o.str("systemPrompt").orEmpty(),
            messages = messages,
            plugins = (o["plugins"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            lastModel = (o["lastUsedModel"] as? JsonObject)?.str("identifier"),
            tokenCount = (o["tokenCount"] as? JsonPrimitive)?.longOrNull ?: 0,
            updatedAt = updatedAt,
            raw = o,
        )
    }

    private fun parseMessage(m: JsonObject?): ChatMessage? {
        m ?: return null
        val versions = (m["versions"] as? JsonArray).orEmpty().mapNotNull { parseVersion(it as? JsonObject) }
        if (versions.isEmpty()) return null
        val sel = (m["currentlySelected"] as? JsonPrimitive)?.longOrNull?.toInt() ?: versions.lastIndex
        return ChatMessage(versions, sel.coerceIn(0, versions.lastIndex))
    }

    private fun textOf(content: JsonArray?): String =
        content.orEmpty().mapNotNull { c -> (c as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }.joinToString("")

    private fun parseVersion(v: JsonObject?): MsgVersion? {
        v ?: return null
        val role = v.str("role") ?: return null
        if (v.str("type") != "multiStep") {
            val full = textOf(v["content"] as? JsonArray)
            val (text, attachments) = splitAttachments(full)
            val pre = (v["preprocessed"] as? JsonObject)?.let { textOf(it["content"] as? JsonArray) }
            return MsgVersion(role, text, attachments = attachments, preprocessed = pre?.takeIf { it != full }, raw = v)
        }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val tools = mutableListOf<ToolUse>()
        var stats: GenStats? = null
        var model: String? = (v["senderInfo"] as? JsonObject)?.str("senderName")
        for (s in (v["steps"] as? JsonArray).orEmpty()) {
            val step = s as? JsonObject ?: continue
            when (step.str("type")) {
                "contentBlock" -> {
                    val t = textOf(step["content"] as? JsonArray)
                    val style = (step["style"] as? JsonObject)?.str("type")
                    when {
                        style == "thinking" -> reasoning.append(t)
                        step.str("roleOverride") == "tool" -> Unit // tool results: shown through the tool chips
                        else -> text.append(t)
                    }
                    (step["genInfo"] as? JsonObject)?.let { g ->
                        g.str("identifier")?.let { model = it }
                        (g["stats"] as? JsonObject)?.let { st ->
                            stats = GenStats(
                                tokensPerSecond = (st["tokensPerSecond"] as? JsonPrimitive)?.doubleOrNull,
                                timeToFirstTokenSec = (st["timeToFirstTokenSec"] as? JsonPrimitive)?.doubleOrNull,
                                predictedTokens = st.int("predictedTokensCount"),
                                promptTokens = st.int("promptTokensCount"),
                                stopReason = st.str("stopReason"),
                                totalTimeSec = (st["totalTimeSec"] as? JsonPrimitive)?.doubleOrNull,
                            )
                        }
                    }
                }
                "toolStatus" -> {
                    val state = step["statusState"] as? JsonObject
                    val type = (state?.get("status") as? JsonObject)?.str("type").orEmpty()
                    tools += ToolUse(
                        name = state?.str("customStatus")?.takeIf { it.isNotBlank() } ?: "Tool call",
                        status = type,
                        ok = !type.contains("fail", ignoreCase = true) && !type.contains("error", ignoreCase = true),
                    )
                }
                "requestConfirmToolCall" -> {
                    val req = step["request"] as? JsonObject
                    val name = req?.str("name") ?: continue
                    if (tools.none { it.name.contains(name) }) tools += ToolUse(name = "${req.str("pluginIdentifier") ?: "tool"}: $name", status = "requested")
                }
            }
        }
        return MsgVersion(role, text.toString(), reasoning.toString(), tools, stats, model, raw = v)
    }

    /** "Hello\n\n📎 a.pdf\n📎 b.png" -> ("Hello", [a.pdf, b.png]) */
    fun splitAttachments(full: String): Pair<String, List<Attachment>> {
        val lines = full.trimEnd().lines()
        var i = lines.size
        while (i > 0 && lines[i - 1].startsWith(ATTACH_MARK)) i--
        if (i == lines.size) return full to emptyList()
        val names = lines.subList(i, lines.size).map { it.removePrefix(ATTACH_MARK).trim() }
        val text = lines.subList(0, i).joinToString("\n").trimEnd()
        return text to names.map { Attachment(name = displayName(it), isImage = isImageName(it), pcName = it) }
    }

    private val uploadStamp = Regex("""^\d{8}-\d{6}-""")

    /** Files are kept on the PC as "20261127-130512-report.pdf"; people see "report.pdf". */
    fun displayName(pcName: String) = uploadStamp.replace(pcName, "")

    fun isImageName(n: String) = n.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "bmp")

    fun displayText(text: String, attachments: List<Attachment>): String =
        if (attachments.isEmpty()) text
        else (if (text.isBlank()) "" else "$text\n\n") + attachments.joinToString("\n") { ATTACH_MARK + (it.pcName ?: it.name) }

    // ---------- writing ----------

    private fun stepId() = "${System.currentTimeMillis()}-${Random.nextDouble()}"

    private fun textItem(text: String) = buildJsonObject {
        put("type", "text"); put("text", text)
        put("fromDraftModel", false); put("tokensCount", approxTokens(text)); put("isStructural", false)
    }

    fun approxTokens(text: String) = (text.length / 4).coerceAtLeast(if (text.isEmpty()) 0 else 1)

    fun encodeVersion(v: MsgVersion): JsonObject {
        v.raw?.let { return it }
        if (v.role != "assistant") {
            val shown = displayText(v.text, v.attachments)
            return buildJsonObject {
                put("type", "singleStep")
                put("role", v.role)
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", shown) }) }
                if (v.preprocessed != null && v.preprocessed != shown) putJsonObject("preprocessed") {
                    put("role", v.role)
                    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", v.preprocessed) }) }
                }
            }
        }
        return buildJsonObject {
            put("type", "multiStep")
            put("role", "assistant")
            putJsonObject("senderInfo") { put("senderName", v.model ?: "Assistant") }
            putJsonArray("steps") {
                if (v.reasoning.isNotBlank()) add(buildJsonObject {
                    put("type", "contentBlock"); put("stepIdentifier", stepId())
                    putJsonArray("content") { add(textItem(v.reasoning)) }
                    put("defaultShouldIncludeInContext", true); put("shouldIncludeInContext", true)
                    put("prefix", ""); put("suffix", "")
                    putJsonObject("style") {
                        put("type", "thinking"); put("ended", true)
                        put("title", v.stats?.timeToFirstTokenSec?.let { "Thought for %.2f seconds".format(it) } ?: "Thoughts")
                        put("forceRenderAsPlainText", false)
                    }
                })
                v.tools.forEachIndexed { i, t ->
                    add(buildJsonObject {
                        put("type", "toolStatus"); put("stepIdentifier", stepId())
                        put("callId", System.currentTimeMillis() * 1000 + i)
                        putJsonObject("statusState") {
                            putJsonObject("status") { put("type", if (t.ok) "toolCallSucceeded" else "toolCallFailed"); put("timeMs", 0) }
                            put("customStatus", t.name)
                            putJsonArray("customWarnings") { }
                        }
                    })
                }
                add(buildJsonObject {
                    put("type", "contentBlock"); put("stepIdentifier", stepId())
                    putJsonArray("content") { add(textItem(v.text)) }
                    put("defaultShouldIncludeInContext", true); put("shouldIncludeInContext", true)
                    put("prefix", ""); put("suffix", "")
                    putJsonObject("genInfo") {
                        put("identifier", v.model ?: "")
                        put("indexedModelIdentifier", v.model ?: "")
                        putJsonObject("stats") {
                            put("stopReason", v.stats?.stopReason ?: "eosFound")
                            v.stats?.tokensPerSecond?.let { put("tokensPerSecond", it) }
                            v.stats?.timeToFirstTokenSec?.let { put("timeToFirstTokenSec", it) }
                            v.stats?.totalTimeSec?.let { put("totalTimeSec", it) }
                            v.stats?.promptTokens?.let { put("promptTokensCount", it) }
                            (v.stats?.predictedTokens ?: approxTokens(v.text)).let { put("predictedTokensCount", it) }
                        }
                    }
                })
            }
        }
    }

    fun encode(d: ChatDoc, now: Long = System.currentTimeMillis()): String {
        val base: Map<String, JsonElement> = if (d.raw.isEmpty()) template(d) else d.raw
        val tokens = d.messages.sumOf { approxTokens(it.current.contextText) + approxTokens(it.current.reasoning) }.toLong()
        val out = LinkedHashMap(base)
        out["name"] = d.name?.let { JsonPrimitive(it) } ?: JsonNull
        out["pinned"] = JsonPrimitive(d.pinned)
        out["createdAt"] = JsonPrimitive(d.createdAt)
        out["systemPrompt"] = JsonPrimitive(d.systemPrompt)
        out["tokenCount"] = JsonPrimitive(maxOf(tokens, if (d.raw.isEmpty()) 0 else d.tokenCount.coerceAtMost(tokens * 2)))
        out["messages"] = buildJsonArray {
            d.messages.forEach { m ->
                add(buildJsonObject {
                    putJsonArray("versions") { m.versions.forEach { add(encodeVersion(it)) } }
                    put("currentlySelected", m.selected.coerceIn(0, m.versions.lastIndex))
                })
            }
        }
        out["plugins"] = buildJsonArray { d.plugins.forEach { add(JsonPrimitive(it)) } }
        if (d.messages.any { it.role == "user" }) out["userLastMessagedAt"] = JsonPrimitive(now)
        if (d.messages.lastOrNull()?.role == "assistant") out["assistantLastMessagedAt"] = JsonPrimitive(now)
        val lm = out["lastUsedModel"] as? JsonObject
        if (d.lastModel != null && lm?.str("identifier") != d.lastModel) {
            out["lastUsedModel"] = buildJsonObject {
                put("identifier", d.lastModel)
                put("indexedModelIdentifier", d.lastModel)
                putJsonObject("instanceLoadTimeConfig") { putJsonArray("fields") { } }
                putJsonObject("instanceOperationTimeConfig") { putJsonArray("fields") { } }
            }
        }
        return JsonObject(out).toString()
    }

    /** A new chat file with every field LM Studio writes itself. */
    private fun template(d: ChatDoc): Map<String, JsonElement> = buildJsonObject {
        put("name", JsonNull)
        put("pinned", false)
        put("createdAt", d.createdAt)
        put("preset", "")
        put("tokenCount", 0)
        put("systemPrompt", "")
        putJsonArray("messages") { }
        put("usePerChatPredictionConfig", true)
        putJsonObject("perChatPredictionConfig") { putJsonArray("fields") { } }
        put("clientInput", "")
        putJsonArray("clientInputFiles") { }
        put("userFilesSizeBytes", 0)
        putJsonArray("notes") { }
        putJsonArray("plugins") { }
        putJsonObject("pluginConfigs") { }
        putJsonArray("disabledPluginTools") { }
        putJsonArray("looseFiles") { }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
