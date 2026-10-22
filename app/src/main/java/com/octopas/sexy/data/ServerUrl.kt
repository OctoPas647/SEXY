package com.octopas.sexy.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns whatever the user typed ("100.64.1.2", "desktop-pc:3030", "http://x:3030/v1/") into a clean base URL. */
object ServerUrl {
    const val DEFAULT_PORT = 3030

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
     *  1. http://name:3030  - llama-server listening on the tailnet, or `tailscale serve --http=3030`
     *  2. https://name      - plain `tailscale serve 3030` (the default: HTTPS on port 443, needs the full *.ts.net name)
     *  3. http://name       - `tailscale serve --http=80 3030`
     * A typed scheme or port is taken literally.
     */
    fun candidates(input: String): List<String> {
        val primary = normalize(input) ?: return emptyList()
        val s = input.trim().replace(" ", "")
        val hadScheme = s.startsWith("http://", true) || s.startsWith("https://", true)
        val typedPort = explicitPort.containsMatchIn(if (hadScheme) s else "http://$s")
        if (hadScheme || typedPort) return listOf(primary)
        val url = primary.toHttpUrlOrNull() ?: return listOf(primary)
        val https = url.newBuilder().scheme("https").port(443).build().toString().trimEnd('/')
        val http80 = url.newBuilder().scheme("http").port(80).build().toString().trimEnd('/')
        return listOf(primary, https, http80).distinct()
    }
}
