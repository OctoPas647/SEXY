package com.octopas.sexy.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Finds the AI server on your tailnet without typing anything: tries every known PC name /
 * Tailscale IP on the default port of every popular AI server (LM Studio, Ollama, llama.cpp,
 * vLLM, Jan, KoboldCpp, text-generation-webui, GPT4All, Open WebUI) at the same time.
 * When several answer, the last-used address wins, then the more popular server.
 */
object ServerDiscovery {
    /** Your PC: its Tailscale (MagicDNS) name and its Tailscale IP, in case MagicDNS is off on the phone. */
    val DEFAULT_HOSTS = listOf("desktop-d0efhda", "100.114.168.92")

    data class Found(val url: String, val models: List<AiModel>, val kind: ServerKind = ServerKind.OPENAI_COMPATIBLE)

    /** Something answered but refused us (e.g. wrong API key) - worth showing instead of "not found". */
    class Refused(val url: String, val error: AiException)

    sealed interface Result {
        data class Success(val found: Found) : Result
        data class Failed(val refused: Refused?, val tried: List<String>) : Result
    }

    /** Every address to try: the last one that worked first, then each host on each port. */
    fun candidates(saved: String?, extraHosts: List<String> = emptyList()): List<String> {
        val hosts = (extraHosts + DEFAULT_HOSTS).map { it.trim() }.filter { it.isNotEmpty() }
        val fromHosts = hosts.flatMap { h ->
            val urls = ServerUrl.candidates(h)
            // Bare names expand to every port; keep http ones (https needs a full *.ts.net name).
            urls.filter { it.startsWith("http://") && !it.endsWith(":80") && it.count { c -> c == ':' } == 2 }
                .ifEmpty { urls.take(1) }
        }
        val last = saved?.takeIf { it.isNotBlank() && !ServerUrl.isRetired(it) }
        return (listOfNotNull(last) + fromHosts).distinct()
    }

    /** Short timeouts: a PC that's off or a wrong port should fail fast. */
    val probeHttp: OkHttpClient = AiClient.defaultHttp.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** After the first server answers, wait this long for a preferred one (earlier in the list). */
    const val GRACE_MS = 1500L

    suspend fun find(
        candidates: List<String>,
        apiKey: String?,
        http: OkHttpClient = probeHttp,
        graceMs: Long = GRACE_MS,
    ): Result = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope Result.Failed(null, emptyList())
        val answers = Channel<Pair<Int, Any>>(Channel.UNLIMITED)
        val jobs = candidates.mapIndexed { i, url ->
            launch {
                val r: Any = try {
                    val info = AiClient(url, apiKey, http).detect()
                    Found(url, info.models, info.kind)
                } catch (e: AiException) {
                    Refused(url, e)
                } catch (e: Exception) {
                    e
                }
                answers.send(i to r)
            }
        }
        val results = arrayOfNulls<Any>(candidates.size)
        var received = 0
        fun best(): Found? = results.firstNotNullOfOrNull { it as? Found }
        /** True when nothing earlier in the list than the best hit is still being checked. */
        fun settled(): Boolean {
            val i = results.indexOfFirst { it is Found }
            return i >= 0 && (0 until i).all { results[it] != null }
        }
        while (received < candidates.size) {
            val (i, r) = answers.receive()
            results[i] = r
            received++
            if (settled()) break
            if (best() != null) {
                // Something answered: give better-placed addresses a short moment, then take the best.
                withTimeoutOrNull(graceMs) {
                    while (received < candidates.size && !settled()) {
                        val (j, r2) = answers.receive()
                        results[j] = r2
                        received++
                    }
                }
                break
            }
        }
        jobs.forEach { it.cancel() }
        best()?.let { return@coroutineScope Result.Success(it) }
        Result.Failed(results.firstNotNullOfOrNull { it as? Refused }, candidates)
    }

    /** Checks several addresses one by one (used for manual entry, where order matters). */
    suspend fun firstWorking(urls: List<String>, apiKey: String?): Result = coroutineScope {
        val results = urls.map { u ->
            async { runCatching { AiClient(u, apiKey, probeHttp).detect().let { Found(u, it.models, it.kind) } } }
        }.awaitAll()
        results.firstNotNullOfOrNull { it.getOrNull() }?.let { return@coroutineScope Result.Success(it) }
        val refused = results.zip(urls).firstNotNullOfOrNull { (r, u) -> (r.exceptionOrNull() as? AiException)?.let { Refused(u, it) } }
        Result.Failed(refused, urls)
    }
}
