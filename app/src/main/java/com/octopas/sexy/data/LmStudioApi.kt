package com.octopas.sexy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A model LM Studio has on disk (GET /api/v1/models). */
data class LmModel(
    val key: String,
    val displayName: String,
    val type: String,
    val publisher: String? = null,
    val architecture: String? = null,
    val quantization: String? = null,
    val bitsPerWeight: Double? = null,
    val sizeBytes: Long = 0,
    val params: String? = null,
    val format: String? = null,
    val maxContext: Int? = null,
    val vision: Boolean = false,
    val tools: Boolean = false,
    val reasoningOptions: List<String> = emptyList(),
    val reasoningDefault: String? = null,
    val loaded: List<LoadedInstance> = emptyList(),
    val description: String? = null,
) {
    val isLoaded get() = loaded.isNotEmpty()
    val isChat get() = type == "llm"
    /** Mixture-of-experts models are the only ones where "experts" applies. */
    val isMoe get() = Regex("""\bA\d+(\.\d+)?B\b|moe""", RegexOption.IGNORE_CASE).containsMatchIn("$key $displayName $architecture")
}

data class LoadedInstance(
    val id: String,
    val contextLength: Int? = null,
    val flashAttention: Boolean? = null,
    val kvOnGpu: Boolean? = null,
    val numExperts: Int? = null,
    val evalBatchSize: Int? = null,
    val parallel: Int? = null,
)

/** Load settings, like LM Studio's load panel. null = LM Studio's own default. */
@kotlinx.serialization.Serializable
data class LoadConfig(
    val contextLength: Int? = null,
    val flashAttention: Boolean? = null,
    val kvOnGpu: Boolean? = null,
    val numExperts: Int? = null,
    val evalBatchSize: Int? = null,
)

data class DownloadJob(
    val jobId: String?,
    val status: String,
    val model: String,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val bytesPerSecond: Double? = null,
) {
    val progress: Float? get() = if (downloadedBytes != null && totalBytes != null && totalBytes > 0) downloadedBytes.toFloat() / totalBytes else null
    val done get() = status == "completed" || status == "already_downloaded"
}

/** One event of LM Studio's /api/v1/chat stream (the endpoint that can use plugins / MCP tools). */
sealed interface NativeEvent {
    data class ModelLoad(val progress: Float) : NativeEvent
    data class PromptProgress(val progress: Float) : NativeEvent
    data class Reasoning(val text: String) : NativeEvent
    data class Message(val text: String) : NativeEvent
    data class ToolStart(val tool: String, val provider: String?) : NativeEvent
    data class ToolDone(val tool: String, val arguments: String, val output: String, val provider: String?) : NativeEvent
    data class ToolFailed(val reason: String) : NativeEvent
    data class End(val stats: GenStats?, val responseId: String?) : NativeEvent
}

/** What the model was asked, for /api/v1/chat. */
data class NativeChatRequest(
    val model: String,
    val text: String,
    val images: List<String> = emptyList(),
    val systemPrompt: String? = null,
    val plugins: List<String> = emptyList(),
    val sampling: Sampling = Sampling(),
    val previousResponseId: String? = null,
)

/** LM Studio's own REST API (v1): models, load/unload, downloads, and chat with tools. */
class LmStudioApi(
    private val baseUrl: String,
    private val apiKey: String?,
    private val http: OkHttpClient = AiClient.defaultHttp,
) {
    private val jsonType = "application/json".toMediaType()

    private fun request(path: String) = Request.Builder()
        .url(baseUrl.trimEnd('/') + path)
        .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }

    private fun call(req: Request): JsonElement =
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(AiClient.errorText(r.code, body), r.code)
            runCatching { appJson.parseToJsonElement(body) }.getOrElse { throw AiException("Unexpected answer from LM Studio") }
        }

    private fun post(path: String, body: JsonObject) =
        call(request(path).post(body.toString().toRequestBody(jsonType)).build())

    suspend fun models(): List<LmModel> = withContext(Dispatchers.IO) {
        val root = call(request("/api/v1/models").get().build())
        parseModels(root)
    }

    suspend fun load(key: String, cfg: LoadConfig): LoadedInstance = withContext(Dispatchers.IO) {
        val o = post("/api/v1/models/load", buildJsonObject {
            put("model", key)
            cfg.contextLength?.let { put("context_length", it) }
            cfg.flashAttention?.let { put("flash_attention", it) }
            cfg.kvOnGpu?.let { put("offload_kv_cache_to_gpu", it) }
            cfg.numExperts?.let { put("num_experts", it) }
            cfg.evalBatchSize?.let { put("eval_batch_size", it) }
            put("echo_load_config", true)
        }) as? JsonObject ?: throw AiException("LM Studio didn't load the model")
        val c = o["load_config"] as? JsonObject
        LoadedInstance(
            id = o.str("instance_id") ?: key,
            contextLength = c?.int("context_length"),
            flashAttention = c?.bool("flash_attention"),
            kvOnGpu = c?.bool("offload_kv_cache_to_gpu"),
            numExperts = c?.int("num_experts"),
            evalBatchSize = c?.int("eval_batch_size"),
        )
    }

    suspend fun unload(instanceId: String) = withContext(Dispatchers.IO) {
        post("/api/v1/models/unload", buildJsonObject { put("instance_id", instanceId) })
        Unit
    }

    suspend fun download(model: String, quantization: String?): DownloadJob = withContext(Dispatchers.IO) {
        val o = post("/api/v1/models/download", buildJsonObject {
            put("model", model)
            quantization?.takeIf { it.isNotBlank() }?.let { put("quantization", it) }
        }) as? JsonObject ?: throw AiException("LM Studio didn't start the download")
        parseJob(o, model)
    }

    suspend fun downloadStatus(job: DownloadJob): DownloadJob = withContext(Dispatchers.IO) {
        val id = job.jobId ?: return@withContext job
        val o = call(request("/api/v1/models/download/status/$id").get().build()) as? JsonObject ?: return@withContext job
        parseJob(o, job.model)
    }

    /** Streams /api/v1/chat. Unlike /v1/chat/completions it runs LM Studio plugins and MCP servers. */
    fun chat(req: NativeChatRequest): Flow<NativeEvent> = flow {
        val body = buildJsonObject {
            put("model", req.model)
            if (req.images.isEmpty()) put("input", req.text) else putJsonArray("input") {
                if (req.text.isNotBlank()) addJsonObject { put("type", "text"); put("content", req.text) }
                req.images.forEach { addJsonObject { put("type", "image"); put("data_url", it) } }
            }
            req.systemPrompt?.takeIf { it.isNotBlank() }?.let { put("system_prompt", it) }
            if (req.plugins.isNotEmpty()) putJsonArray("integrations") { req.plugins.forEach { add(JsonPrimitive(it)) } }
            put("stream", true)
            put("store", true)
            req.previousResponseId?.let { put("previous_response_id", it) }
            val s = req.sampling
            s.temperature?.let { put("temperature", it) }
            s.topP?.let { put("top_p", it) }
            s.topK?.let { put("top_k", it) }
            s.minP?.let { put("min_p", it) }
            s.repeatPenalty?.let { put("repeat_penalty", it) }
            s.maxTokens?.let { put("max_output_tokens", it) }
            s.contextLength?.let { put("context_length", it) }
            if (s.reasoning != Reasoning.AUTO) put("reasoning", s.reasoning.api)
        }
        val call = http.newCall(
            request("/api/v1/chat").header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody(jsonType)).build()
        )
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { r ->
            if (!r.isSuccessful) throw AiException(AiClient.errorText(r.code, r.body?.string().orEmpty()), r.code)
            val source = r.body?.source() ?: throw AiException("Empty response from LM Studio")
            while (true) {
                val line = source.readUtf8Line() ?: break
                val ev = NativeSse.parse(line) ?: continue
                emit(ev)
                if (ev is NativeEvent.End) break
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        fun parseModels(root: JsonElement): List<LmModel> {
            val arr = ((root as? JsonObject)?.get("models") as? JsonArray) ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val key = o.str("key") ?: return@mapNotNull null
                val q = o["quantization"] as? JsonObject
                val caps = o["capabilities"] as? JsonObject
                val reasoning = (caps?.get("reasoning") ?: o["reasoning"]) as? JsonObject
                LmModel(
                    key = key,
                    displayName = o.str("display_name") ?: key,
                    type = o.str("type") ?: "llm",
                    publisher = o.str("publisher"),
                    architecture = o.str("architecture"),
                    quantization = q?.str("name"),
                    bitsPerWeight = (q?.get("bits_per_weight") as? JsonPrimitive)?.doubleOrNull,
                    sizeBytes = (o["size_bytes"] as? JsonPrimitive)?.longOrNull ?: 0,
                    params = o.str("params_string"),
                    format = o.str("format"),
                    maxContext = o.int("max_context_length"),
                    vision = caps?.bool("vision") == true,
                    tools = caps?.bool("trained_for_tool_use") == true,
                    reasoningOptions = (reasoning?.get("allowed_options") as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                    reasoningDefault = reasoning?.str("default"),
                    loaded = (o["loaded_instances"] as? JsonArray).orEmpty().mapNotNull { i ->
                        val io = i as? JsonObject ?: return@mapNotNull null
                        val c = io["config"] as? JsonObject
                        LoadedInstance(
                            id = io.str("id") ?: key,
                            contextLength = c?.int("context_length"),
                            flashAttention = c?.bool("flash_attention"),
                            kvOnGpu = c?.bool("offload_kv_cache_to_gpu"),
                            numExperts = c?.int("num_experts"),
                            evalBatchSize = c?.int("eval_batch_size"),
                            parallel = c?.int("parallel"),
                        )
                    },
                    description = o.str("description"),
                )
            }.sortedWith(compareByDescending<LmModel> { it.isLoaded }.thenBy { it.displayName.lowercase() })
        }

        fun parseJob(o: JsonObject, model: String) = DownloadJob(
            jobId = o.str("job_id"),
            status = o.str("status") ?: "downloading",
            model = model,
            downloadedBytes = (o["downloaded_bytes"] as? JsonPrimitive)?.longOrNull,
            totalBytes = (o["total_size_bytes"] as? JsonPrimitive)?.longOrNull,
            bytesPerSecond = (o["bytes_per_second"] as? JsonPrimitive)?.doubleOrNull,
        )
    }
}

/** Parses the "data:" lines of /api/v1/chat (every event carries its own "type"). Pure, so it's unit-tested. */
object NativeSse {
    fun parse(line: String): NativeEvent? {
        if (!line.startsWith("data:")) return null
        val o = runCatching { appJson.parseToJsonElement(line.removePrefix("data:").trim()) as? JsonObject }.getOrNull() ?: return null
        fun provider(): String? = (o["provider_info"] as? JsonObject)?.let { it.str("plugin_id") ?: it.str("server_label") }
        return when (o.str("type")) {
            "model_load.progress" -> NativeEvent.ModelLoad((o["progress"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f)
            "model_load.start" -> NativeEvent.ModelLoad(0f)
            "model_load.end" -> NativeEvent.ModelLoad(1f)
            "prompt_processing.progress" -> NativeEvent.PromptProgress((o["progress"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f)
            "prompt_processing.start" -> NativeEvent.PromptProgress(0f)
            "prompt_processing.end" -> NativeEvent.PromptProgress(1f)
            "reasoning.delta" -> o.str("content")?.let { NativeEvent.Reasoning(it) }
            "message.delta" -> o.str("content")?.let { NativeEvent.Message(it) }
            "tool_call.start" -> NativeEvent.ToolStart(o.str("tool") ?: "tool", provider())
            "tool_call.success" -> NativeEvent.ToolDone(
                o.str("tool") ?: "tool", o["arguments"]?.toString() ?: "{}", o.str("output").orEmpty(), provider(),
            )
            "tool_call.failure" -> NativeEvent.ToolFailed(o.str("reason") ?: "Tool call failed")
            "error" -> {
                val err = o["error"] as? JsonObject
                throw AiException(err?.str("message") ?: "LM Studio error")
            }
            "chat.end" -> {
                val result = o["result"] as? JsonObject
                val st = result?.get("stats") as? JsonObject
                NativeEvent.End(
                    stats = st?.let {
                        GenStats(
                            tokensPerSecond = (it["tokens_per_second"] as? JsonPrimitive)?.doubleOrNull,
                            timeToFirstTokenSec = (it["time_to_first_token_seconds"] as? JsonPrimitive)?.doubleOrNull,
                            predictedTokens = it.int("total_output_tokens"),
                            promptTokens = it.int("input_tokens"),
                            stopReason = "eosFound",
                        )
                    },
                    responseId = result?.str("response_id"),
                )
            }
            else -> null
        }
    }
}

internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
