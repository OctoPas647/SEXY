package com.octopas.sexy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMsg(val role: String, val content: String)

/** A chat model the server offers. [loaded] = already in memory on the PC (answers right away). */
data class AiModel(val id: String, val loaded: Boolean = false)

/**
 * The kinds of AI server SEXY recognises. Almost all of them speak the OpenAI API under /v1;
 * Open WebUI is the exception (its API lives under /api). [ports] are the defaults the app searches.
 */
enum class ServerKind(val label: String, val chatPath: String, val ports: List<Int>) {
    LM_STUDIO("LM Studio", "/v1/chat/completions", listOf(1234)),
    OLLAMA("Ollama", "/v1/chat/completions", listOf(11434)),
    LLAMA_CPP("llama.cpp", "/v1/chat/completions", listOf(8080)),
    VLLM("vLLM", "/v1/chat/completions", listOf(8000)),
    JAN("Jan", "/v1/chat/completions", listOf(1337)),
    KOBOLDCPP("KoboldCpp", "/v1/chat/completions", listOf(5001)),
    TEXT_GEN_WEBUI("text-generation-webui", "/v1/chat/completions", listOf(5000)),
    GPT4ALL("GPT4All", "/v1/chat/completions", listOf(4891)),
    LOCALAI("LocalAI", "/v1/chat/completions", emptyList()),
    OPEN_WEBUI("Open WebUI", "/api/chat/completions", listOf(3000, 8080)),
    OPENAI_COMPATIBLE("AI server", "/v1/chat/completions", emptyList());

    companion object {
        fun fromName(name: String?): ServerKind? = entries.firstOrNull { it.name == name }

        /** Every default port, most popular first, without duplicates. */
        val allPorts: List<Int> get() = entries.flatMap { it.ports }.distinct()

        /** Guess the server from the "owned_by" field of /v1/models (each server fills it differently). */
        fun fromOwner(owner: String?): ServerKind? {
            val o = owner?.lowercase() ?: return null
            return when {
                "llamacpp" in o || "llama.cpp" in o || "llama-cpp" in o -> LLAMA_CPP
                "vllm" in o -> VLLM
                "ollama" in o || o == "library" -> OLLAMA
                "koboldcpp" in o -> KOBOLDCPP
                "localai" in o -> LOCALAI
                "jan" == o || "jan" in o.split(' ', '-', '_') -> JAN
                "gpt4all" in o -> GPT4ALL
                "openwebui" in o || "open-webui" in o -> OPEN_WEBUI
                "lmstudio" in o || "lm-studio" in o || "lm studio" in o -> LM_STUDIO
                else -> null
            }
        }
    }
}

/** What answered at an address, and the chat models it has. */
data class ServerInfo(val kind: ServerKind, val models: List<AiModel>)

sealed interface StreamEvent {
    data class Content(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data class Stats(val tokensPerSecond: Double?, val promptTokens: Int?, val completionTokens: Int?) : StreamEvent
}

class AiException(message: String, val code: Int = 0) : Exception(message)

val appJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/**
 * Talks to whatever AI server runs on the PC: LM Studio, Ollama, llama.cpp, vLLM, Jan, KoboldCpp,
 * text-generation-webui, GPT4All, LocalAI, Open WebUI or any other OpenAI-compatible server.
 * [detect] works out which one it is by asking each server's own "list models" endpoint.
 * [kind] = already known (saved from an earlier detect), so chat goes to the right path.
 */
class AiClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val http: OkHttpClient = defaultHttp,
    private val clock: () -> Long = System::nanoTime,
    private val kind: ServerKind? = null,
) {
    private fun request(path: String) = Request.Builder()
        .url(baseUrl.trimEnd('/') + path)
        .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }

    /** Model ids only (chat models, loaded ones first). */
    suspend fun listModels(): List<String> = models().map { it.id }

    suspend fun models(): List<AiModel> = detect().models

    /**
     * Finds out which server this is and lists its chat models. Each server is asked in its own
     * language, most specific first, so the richest information wins (e.g. which models are loaded).
     * A refused connection or timeout is thrown straight away - nothing else will answer there either.
     */
    suspend fun detect(): ServerInfo = withContext(Dispatchers.IO) {
        var refused: AiException? = null
        var lastError: AiException? = null
        val probes: List<() -> ServerInfo?> = listOf(::lmStudio, ::ollama, ::openAi, ::openWebUi, ::koboldLegacy)
        for (probe in probes) {
            try {
                probe()?.let { return@withContext it }
            } catch (e: AiException) {
                // A proxy (tailscale serve) saying the server behind it is down: every path will say the same.
                if (e.code in 502..504) throw e
                if (e.code == 401 || e.code == 403) { if (refused == null) refused = e } else lastError = e
            }
        }
        throw refused ?: lastError ?: AiException("That address answered, but it isn't an AI server.")
    }

    /** GET [path] as JSON. Null when the endpoint doesn't exist or doesn't return JSON (e.g. a web page). */
    private fun getJson(path: String): JsonElement? =
        http.newCall(request(path).get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw AiException(errorText(r.code, body), r.code)
            if (r.code == 502 || r.code == 503 || r.code == 504) throw AiException(errorText(r.code, body), r.code)
            if (!r.isSuccessful) return null
            runCatching { appJson.parseToJsonElement(body) }.getOrNull()
        }

    /** LM Studio: /api/v0/models also says which models are loaded and which are embeddings. */
    private fun lmStudio(): ServerInfo? {
        val data = ((getJson("/api/v0/models") as? JsonObject)?.get("data") as? JsonArray) ?: return null
        val looksLikeLmStudio = data.isEmpty() || data.any { (it as? JsonObject)?.containsKey("state") == true }
        if (!looksLikeLmStudio) return null
        return ServerInfo(ServerKind.LM_STUDIO, ModelList.fromLmStudio(data))
    }

    /** Ollama: /api/tags lists downloaded models, /api/ps the ones in memory. */
    private fun ollama(): ServerInfo? {
        val tags = ((getJson("/api/tags") as? JsonObject)?.get("models") as? JsonArray) ?: return null
        // llama.cpp's server copies /api/tags too, but only Ollama answers /api/version.
        val version = runCatching { (getJson("/api/version") as? JsonObject)?.get("version") }.getOrNull()
        if (version == null) return null
        val running = runCatching { ((getJson("/api/ps") as? JsonObject)?.get("models") as? JsonArray) }.getOrNull()
        return ServerInfo(ServerKind.OLLAMA, ModelList.fromOllama(tags, running ?: JsonArray(emptyList())))
    }

    /** The OpenAI-style /v1/models that nearly every server has. "owned_by" tells them apart. */
    private fun openAi(): ServerInfo? {
        val root = getJson("/v1/models") ?: return null
        val list = ModelList.openAiArray(root) ?: return null
        val owner = list.firstNotNullOfOrNull { ((it as? JsonObject)?.get("owned_by") as? JsonPrimitive)?.contentOrNull }
        val kind = ServerKind.fromOwner(owner)
            ?: if ((root as? JsonObject)?.containsKey("models") == true) ServerKind.LLAMA_CPP else ServerKind.OPENAI_COMPATIBLE
        return ServerInfo(kind, ModelList.fromOpenAi(list))
    }

    /** Open WebUI keeps its API under /api and needs an API key (Settings -> Account -> API keys). */
    private fun openWebUi(): ServerInfo? {
        val data = ((getJson("/api/models") as? JsonObject)?.get("data") as? JsonArray) ?: return null
        return ServerInfo(ServerKind.OPEN_WEBUI, ModelList.fromOpenAi(data))
    }

    /** Older KoboldCpp builds without /v1: one model, named by /api/v1/model. */
    private fun koboldLegacy(): ServerInfo? {
        val name = ((getJson("/api/v1/model") as? JsonObject)?.get("result") as? JsonPrimitive)?.contentOrNull
            ?: return null
        return ServerInfo(ServerKind.KOBOLDCPP, listOf(AiModel(name, loaded = true)))
    }

    fun streamChat(
        model: String,
        messages: List<ChatMsg>,
        thinking: Boolean,
        maxTokens: Int? = null,
    ): Flow<StreamEvent> = flow {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
            }
            putJsonObject("chat_template_kwargs") { put("enable_thinking", thinking) }
            putJsonObject("stream_options") { put("include_usage", true) }
            if (maxTokens != null) put("max_tokens", maxTokens)
        }
        val call = http.newCall(
            request((kind ?: ServerKind.OPENAI_COMPATIBLE).chatPath)
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        )
        // Stop button -> coroutine cancelled -> abort the HTTP stream (the server stops generating too).
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        val think = ThinkTagSplitter()
        var stats: StreamEvent.Stats? = null
        var firstTokenAt: Long? = null
        var chunks = 0
        call.execute().use { r ->
            if (!r.isSuccessful) throw AiException(errorText(r.code, r.body?.string().orEmpty()), r.code)
            val source = r.body?.source() ?: throw AiException("Empty response from server")
            loop@ while (true) {
                val line = source.readUtf8Line() ?: break
                when (val res = SseParser.parse(line)) {
                    SseParser.Result.Done -> break@loop
                    SseParser.Result.Skip -> Unit
                    is SseParser.Result.Events -> for (ev in res.events) {
                        when (ev) {
                            is StreamEvent.Stats -> stats = merge(stats, ev)
                            is StreamEvent.Reasoning, is StreamEvent.Content -> {
                                if (firstTokenAt == null) firstTokenAt = clock()
                                chunks++
                                val split = if (ev is StreamEvent.Content) think.feed(ev.text) else listOf(ev)
                                split.forEach { emit(it) }
                            }
                        }
                    }
                }
            }
        }
        think.flush().forEach { emit(it) }
        // LM Studio doesn't send a speed, so work it out from the time between the first and last token.
        val elapsed = firstTokenAt?.let { (clock() - it) / 1e9 } ?: 0.0
        val final = when {
            stats?.tokensPerSecond != null -> stats
            elapsed > 0.2 -> StreamEvent.Stats(
                tokensPerSecond = (stats?.completionTokens ?: chunks) / elapsed,
                promptTokens = stats?.promptTokens,
                completionTokens = stats?.completionTokens,
            )
            else -> stats
        }
        final?.let { emit(it) }
    }.flowOn(Dispatchers.IO)

    private fun merge(a: StreamEvent.Stats?, b: StreamEvent.Stats) = StreamEvent.Stats(
        tokensPerSecond = b.tokensPerSecond ?: a?.tokensPerSecond,
        promptTokens = b.promptTokens ?: a?.promptTokens,
        completionTokens = b.completionTokens ?: a?.completionTokens,
    )

    companion object {
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES) // loading a model / big prompts can take a while before the first token
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        fun errorText(code: Int, body: String): String {
            val serverMsg = runCatching {
                val o = appJson.parseToJsonElement(body) as? JsonObject
                val err = o?.get("error")
                ((err as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (err as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
            return when (code) {
                401, 403 -> "The server wants an API key (or the key is wrong). LM Studio: Developer → Server settings → API tokens. Open WebUI: Settings → Account → API keys."
                502, 504 -> "Your PC is reachable, but the AI server isn't running. For LM Studio: open it → Developer → Start server, or run D:\\AI\\LMStudio\\SEXY-LMSTUDIO.bat."
                503 -> "The model is still loading on your PC. Try again in a few seconds."
                404 -> serverMsg?.let { "Server: $it" } ?: "That address answered, but it isn't an AI server (404)."
                else -> "Server error $code" + (serverMsg?.let { ": $it" } ?: body.take(200).let { if (it.isBlank()) "" else ": $it" })
            }
        }
    }
}

/** Turns the servers' model lists into chat models, loaded ones first. Pure Kotlin so it's unit-tested. */
object ModelList {
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun isChatId(id: String) = !id.contains("embed", ignoreCase = true)

    fun fromLmStudio(data: JsonArray): List<AiModel> = data.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        val type = o.str("type")
        if (type != null && type != "llm" && type != "vlm") return@mapNotNull null
        if (!isChatId(id)) return@mapNotNull null
        AiModel(id, loaded = o.str("state") == "loaded")
    }.sortedByDescending { it.loaded }

    fun fromOpenAi(data: JsonArray): List<AiModel> = data.mapNotNull { e ->
        val o = e as? JsonObject
        val id = o?.str("id") ?: o?.str("model") ?: o?.str("name") ?: (e as? JsonPrimitive)?.contentOrNull
            ?: return@mapNotNull null
        if (isChatId(id)) AiModel(id) else null
    }.distinctBy { it.id }

    /** The model array of a /v1/models answer: {"data":[...]}, {"models":[...]} or a bare [...]. */
    fun openAiArray(root: JsonElement): JsonArray? = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
        else -> null
    }

    /** Ollama: downloaded models from /api/tags, marked loaded when /api/ps lists them. */
    fun fromOllama(tags: JsonArray, running: JsonArray): List<AiModel> {
        val loaded = running.mapNotNull { (it as? JsonObject)?.let { o -> o.str("name") ?: o.str("model") } }.toSet()
        return tags.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("name") ?: o.str("model") ?: return@mapNotNull null
            val family = (o["details"] as? JsonObject)?.str("family").orEmpty()
            if (!isChatId(id) || family.contains("bert", ignoreCase = true)) return@mapNotNull null
            AiModel(id, loaded = id in loaded)
        }.sortedByDescending { it.loaded }
    }

    /** Keep the user's pick if the server still has it, else a loaded model, else the first one. */
    fun choose(models: List<AiModel>, previous: String?): String? =
        models.firstOrNull { it.id == previous }?.id
            ?: models.firstOrNull { it.loaded }?.id
            ?: models.firstOrNull()?.id
}

/**
 * Some servers put the model's thinking inside the answer as <think>...</think> instead of a
 * separate field. This moves that part into Reasoning events, even when a tag is split across chunks.
 */
class ThinkTagSplitter {
    private var inThink = false
    private var pending = ""

    fun feed(text: String): List<StreamEvent> {
        val out = mutableListOf<StreamEvent>()
        var s = pending + text
        pending = ""
        while (s.isNotEmpty()) {
            val tag = if (inThink) "</think>" else "<think>"
            val i = s.indexOf(tag)
            if (i >= 0) {
                emit(out, s.substring(0, i))
                inThink = !inThink
                s = s.substring(i + tag.length)
                continue
            }
            // Hold back a possible partial tag at the end ("<thi") until the next chunk.
            val keep = (1 until tag.length).lastOrNull { s.endsWith(tag.substring(0, it)) } ?: 0
            emit(out, s.substring(0, s.length - keep))
            pending = s.substring(s.length - keep)
            break
        }
        return out
    }

    fun flush(): List<StreamEvent> {
        val out = mutableListOf<StreamEvent>()
        emit(out, pending)
        pending = ""
        return out
    }

    private fun emit(out: MutableList<StreamEvent>, t: String) {
        if (t.isEmpty()) return
        out += if (inThink) StreamEvent.Reasoning(t) else StreamEvent.Content(t)
    }
}

/** Parses one line of the server-sent-events stream. Pure Kotlin so it is unit-tested. */
object SseParser {
    sealed interface Result {
        data object Skip : Result
        data object Done : Result
        data class Events(val events: List<StreamEvent>) : Result
    }

    fun parse(line: String): Result {
        if (line.startsWith("error:")) throw AiException(line.removePrefix("error:").trim())
        if (!line.startsWith("data:")) return Result.Skip
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return Result.Skip
        if (data == "[DONE]") return Result.Done
        val obj = runCatching { appJson.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return Result.Skip

        obj["error"]?.let { e ->
            val msg = ((e as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: e.toString()
            throw AiException(msg)
        }

        val out = mutableListOf<StreamEvent>()
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject
        if (delta != null) {
            // llama-server sends "reasoning_content"; LM Studio and others may send "reasoning".
            ((delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }?.let { out += StreamEvent.Reasoning(it) }
            (delta["content"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotEmpty() }?.let { out += StreamEvent.Content(it) }
        }
        val timings = obj["timings"] as? JsonObject
        val usage = obj["usage"] as? JsonObject
        if (timings != null || usage != null) {
            fun JsonObject?.int(k: String) = (this?.get(k) as? JsonPrimitive)?.intOrNull
            out += StreamEvent.Stats(
                tokensPerSecond = (timings?.get("predicted_per_second") as? JsonPrimitive)?.doubleOrNull,
                promptTokens = usage.int("prompt_tokens") ?: timings.int("prompt_n"),
                completionTokens = usage.int("completion_tokens") ?: timings.int("predicted_n"),
            )
        }
        return if (out.isEmpty()) Result.Skip else Result.Events(out)
    }
}
