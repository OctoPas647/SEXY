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
}
