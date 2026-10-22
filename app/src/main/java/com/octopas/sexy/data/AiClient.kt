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

sealed interface StreamEvent {
    data class Content(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data class Stats(val tokensPerSecond: Double?, val promptTokens: Int?, val completionTokens: Int?) : StreamEvent
}

class AiException(message: String) : Exception(message)

val appJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** Talks to llama-server's OpenAI-compatible API (/v1/models, /v1/chat/completions). */
class AiClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val http: OkHttpClient = defaultHttp,
) {
    private fun request(path: String) = Request.Builder()
        .url(baseUrl.trimEnd('/') + path)
        .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }

    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        http.newCall(request("/v1/models").get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(errorText(r.code, body))
            val root = runCatching { appJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw AiException("That address answered, but it isn't an AI server.")
            (root["data"] as? JsonArray).orEmpty().mapNotNull { m ->
                ((m as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
            }
        }
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
            request("/v1/chat/completions")
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        )
        // Stop button -> coroutine cancelled -> abort the HTTP stream (the server stops generating too).
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { r ->
            if (!r.isSuccessful) throw AiException(errorText(r.code, r.body?.string().orEmpty()))
            val source = r.body?.source() ?: throw AiException("Empty response from server")
            while (true) {
                val line = source.readUtf8Line() ?: break
                when (val res = SseParser.parse(line)) {
                    SseParser.Result.Done -> return@use
                    SseParser.Result.Skip -> Unit
                    is SseParser.Result.Events -> res.events.forEach { emit(it) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES) // big prompts can take a while before the first token
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
                401 -> "Wrong API key. Copy it again from D:\\AI\\api-key.txt."
                502, 504 -> "Your PC is reachable, but the AI server isn't running. Start '2-START AI SERVER.bat' on the PC."
                503 -> "The model is still loading on your PC. Try again in a few seconds."
                404 -> "That address answered, but it isn't the AI server (404)."
                else -> "Server error $code" + (serverMsg?.let { ": $it" } ?: body.take(200).let { if (it.isBlank()) "" else ": $it" })
            }
        }
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
            (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
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
