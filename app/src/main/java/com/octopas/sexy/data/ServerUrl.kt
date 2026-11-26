package com.octopas.sexy.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns whatever the user typed ("100.64.1.2", "desktop-pc:1234", "http://x:1234/v1/") into a clean base URL. */
object ServerUrl {
    /** LM Studio's server port. */
    const val DEFAULT_PORT = 1234

    /** Ports a server on the PC may use: LM Studio first, then the old llama-server setup. */
    val KNOWN_PORTS = listOf(1234, 3030)

    private val schemeOnly = Regex("^https?:?/*$", RegexOption.IGNORE_CASE)
    private val explicitPort = Regex("^https?://(\\[[^\\]]+]|[^/:]+):\\d+", RegexOption.IGNORE_CASE)

    fun normalize(input: String): String? {
        var s = input.trim().replace(" ", "")
        if (s.isEmpty() || schemeOnly.matches(s)) return null
        s = s.trimEnd('/')
        if (s.endsWith("/v1", ignoreCase = true)) s = s.dropLast(3).trimEnd('/')
        val hadScheme = s.startsWith("http://", true) || s.startsWith("https://", true)
        if (!hadScheme) s = "http://$s"
        val url = s.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        val typedPort = explicitPort.containsMatchIn(s)
        val builder = url.newBuilder()
        if (!typedPort && !hadScheme) builder.port(DEFAULT_PORT)
        return builder.build().toString().trimEnd('/')
    }

    /**
     * Addresses to try, in order, for what the user typed. With no scheme and no port the server
     * could be shared in several ways, so all of them are tried:
     *  1. http://name:1234  - LM Studio (shared with `tailscale serve --http=1234` or "serve on local network")
     *  2. http://name:3030  - the old llama-server setup
     *  3. https://name      - plain `tailscale serve` (HTTPS on port 443, needs the full *.ts.net name)
     *  4. http://name       - `tailscale serve --http=80`
     * A typed scheme or port is taken literally.
     */
    fun candidates(input: String): List<String> {
        val primary = normalize(input) ?: return emptyList()
        val s = input.trim().replace(" ", "")
        val hadScheme = s.startsWith("http://", true) || s.startsWith("https://", true)
        val typedPort = explicitPort.containsMatchIn(if (hadScheme) s else "http://$s")
        if (hadScheme || typedPort) return listOf(primary)
        val url = primary.toHttpUrlOrNull() ?: return listOf(primary)
        val ports = KNOWN_PORTS.map { url.newBuilder().port(it).build().toString().trimEnd('/') }
        val https = url.newBuilder().scheme("https").port(443).build().toString().trimEnd('/')
        val http80 = url.newBuilder().scheme("http").port(80).build().toString().trimEnd('/')
        return (ports + https + http80).distinct()
    }
}
