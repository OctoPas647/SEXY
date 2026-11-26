package com.octopas.sexy.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Finds the AI server on your tailnet without typing anything: tries every known
 * PC name / Tailscale IP on every known port at the same time and takes the first that answers.
 */
object ServerDiscovery {
    /** Your PC: its Tailscale (MagicDNS) name and its Tailscale IP, in case MagicDNS is off on the phone. */
    val DEFAULT_HOSTS = listOf("desktop-d0efhda", "100.114.168.92")

    data class Found(val url: String, val models: List<AiModel>)

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
        return (listOfNotNull(saved?.takeIf { it.isNotBlank() }) + fromHosts).distinct()
    }

    /** Short timeouts: a PC that's off or a wrong port should fail fast. */
    val probeHttp: OkHttpClient = AiClient.defaultHttp.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun find(
        candidates: List<String>,
        apiKey: String?,
        http: OkHttpClient = probeHttp,
    ): Result = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope Result.Failed(null, emptyList())
        val answers = Channel<Any>(Channel.UNLIMITED)
        val jobs = candidates.map { url ->
            launch {
                val r: Any = try {
                    Found(url, AiClient(url, apiKey, http).models())
                } catch (e: AiException) {
                    Refused(url, e)
                } catch (e: Exception) {
                    e
                }
                answers.send(r)
            }
        }
        var refused: Refused? = null
        repeat(candidates.size) {
            when (val a = answers.receive()) {
                is Found -> {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope Result.Success(a)
                }
                is Refused -> if (refused == null) refused = a
            }
        }
        Result.Failed(refused, candidates)
    }

    /** Checks several addresses one by one (used for manual entry, where order matters). */
    suspend fun firstWorking(urls: List<String>, apiKey: String?): Result = coroutineScope {
        val results = urls.map { u -> async { runCatching { Found(u, AiClient(u, apiKey, probeHttp).models()) } } }.awaitAll()
        results.firstNotNullOfOrNull { it.getOrNull() }?.let { return@coroutineScope Result.Success(it) }
        val refused = results.zip(urls).firstNotNullOfOrNull { (r, u) -> (r.exceptionOrNull() as? AiException)?.let { Refused(u, it) } }
        Result.Failed(refused, urls)
    }
}
