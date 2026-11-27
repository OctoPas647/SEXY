package com.octopas.sexy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class PluginInfo(val id: String, val name: String = id, val source: String = "plugin")

@Serializable
data class TerminalResult(val output: String = "", val exitCode: Int = 0, val cwd: String = "", val timedOut: Boolean = false)

@Serializable
data class UploadedFile(val name: String, val path: String = "", val size: Long = 0)

class BridgeException(message: String, val code: Int = 0) : Exception(message)

/** The PC changed the chat after we last read it. */
class ChatConflict(val pcUpdatedAt: Long) : Exception("Changed on the PC")

/**
 * Talks to sexy-bridge.ps1 on the PC (port 1235): LM Studio's chat folder, plugins, uploads and the terminal.
 * Only reachable over Tailscale (tailscale serve), never from the internet.
 */
class BridgeClient(
    private val baseUrl: String,
    private val http: OkHttpClient = defaultHttp,
) {
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = baseUrl.trimEnd('/') + path
    private fun enc(id: String) = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun exec(req: Request): Pair<String, okhttp3.Headers> = http.newCall(req).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (r.code == 409) {
            val t = runCatching { ((appJson.parseToJsonElement(body) as JsonObject)["updatedAt"] as JsonPrimitive).longOrNull }.getOrNull()
            throw ChatConflict(t ?: 0)
        }
        if (!r.isSuccessful) {
            val msg = runCatching { ((appJson.parseToJsonElement(body) as JsonObject)["error"] as JsonPrimitive).content }.getOrNull()
            throw BridgeException(msg ?: "PC bridge error ${r.code}", r.code)
        }
        body to r.headers
    }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching { exec(Request.Builder().url(url("/health")).build()).first.contains("sexy-bridge") }.getOrDefault(false)
    }

    suspend fun list(): List<ChatSummary> = withContext(Dispatchers.IO) {
        val (body, _) = exec(Request.Builder().url(url("/conversations")).build())
        appJson.decodeFromString(ListSerializer(ChatSummary.serializer()), body)
    }

    suspend fun get(id: String): ChatDoc = withContext(Dispatchers.IO) {
        val (body, headers) = exec(Request.Builder().url(url("/conversations/${enc(id)}")).build())
        LmsFormat.parse(id, body, headers["X-Updated-At"]?.toLongOrNull() ?: 0)
    }

    /** Saves the chat; returns the PC's new file time. Throws [ChatConflict] if the PC changed it after [baseUpdatedAt]. */
    suspend fun put(id: String, json: String, baseUpdatedAt: Long?): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/conversations/${enc(id)}"))
            .put(json.toRequestBody(jsonType))
            .apply { if (baseUpdatedAt != null && baseUpdatedAt > 0) header("X-Base-Updated-At", baseUpdatedAt.toString()) }
            .build()
        val (_, headers) = exec(req)
        headers["X-Updated-At"]?.toLongOrNull() ?: System.currentTimeMillis()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        exec(Request.Builder().url(url("/conversations/${enc(id)}")).delete().build())
        Unit
    }

    suspend fun plugins(): List<PluginInfo> = withContext(Dispatchers.IO) {
        val (body, _) = exec(Request.Builder().url(url("/plugins")).build())
        appJson.decodeFromString(ListSerializer(PluginInfo.serializer()), body)
    }

    /** Stores a file on the PC (next to LM Studio, in sexy-uploads). Returns its name there. */
    suspend fun upload(name: String, bytes: ByteArray): UploadedFile = withContext(Dispatchers.IO) {
        val (body, _) = exec(
            Request.Builder().url(url("/files/${enc(name)}"))
                .put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        )
        appJson.decodeFromString(UploadedFile.serializer(), body)
    }

    suspend fun download(pcName: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url("/files/${enc(pcName)}")).build()).execute().use { r ->
            if (!r.isSuccessful) throw BridgeException("File not on the PC any more", r.code)
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun files(): List<UploadedFile> = withContext(Dispatchers.IO) {
        val (body, _) = exec(Request.Builder().url(url("/files")).build())
        appJson.decodeFromString(ListSerializer(UploadedFile.serializer()), body)
    }

    /** Runs a PowerShell command on the PC in [cwd] and returns its output. */
    suspend fun run(command: String, cwd: String?, timeoutSec: Int = 120): TerminalResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("command", command)
            cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            put("timeout", timeoutSec)
        }
        val client = http.newBuilder().readTimeout((timeoutSec + 15).toLong(), TimeUnit.SECONDS).build()
        val (text, _) = client.newCall(
            Request.Builder().url(url("/terminal")).post(body.toString().toRequestBody(jsonType)).build()
        ).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { ((appJson.parseToJsonElement(b) as JsonObject)["error"] as JsonPrimitive).content }.getOrNull()
                throw BridgeException(msg ?: "Terminal error ${r.code}", r.code)
            }
            b to r.headers
        }
        appJson.decodeFromString(TerminalResult.serializer(), text)
    }

    companion object {
        const val PORT = 1235

        val defaultHttp: OkHttpClient = AiClient.defaultHttp.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** The bridge runs on the same PC as the AI server, on its own port. */
        fun urlFor(serverUrl: String?): String? {
            val u = serverUrl?.toHttpUrlOrNull() ?: return null
            return u.newBuilder().scheme("http").port(PORT).encodedPath("/").build().toString().trimEnd('/')
        }
    }
}
