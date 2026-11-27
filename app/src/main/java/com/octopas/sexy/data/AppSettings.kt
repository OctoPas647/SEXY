package com.octopas.sexy.data

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(v) = prefs.edit().putString("server_url", v).apply()

    /** Which kind of server answered last (LM Studio, Ollama, ...), so chat uses the right API path. */
    var serverKind: ServerKind?
        get() = ServerKind.fromName(prefs.getString("server_kind", null))
        set(v) = prefs.edit().putString("server_kind", v?.name).apply()

    var apiKey: String?
        get() = secure.get("api_key")
        set(v) = secure.put("api_key", v)

    var modelId: String?
        get() = prefs.getString("model_id", null)
        set(v) = prefs.edit().putString("model_id", v).apply()

    var githubToken: String?
        get() = secure.get("github_token")
        set(v) = secure.put("github_token", v)

    var githubLogin: String?
        get() = prefs.getString("github_login", null)
        set(v) = prefs.edit().putString("github_login", v).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt", "") ?: ""
        set(v) = prefs.edit().putString("system_prompt", v).apply()

    /** Extra PC names / Tailscale IPs to look for, besides the built-in ones (comma separated). */
    var extraHosts: List<String>
        get() = prefs.getString("extra_hosts", "").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("extra_hosts", v.joinToString(",")).apply()

    var thinking: Boolean
        get() = prefs.getBoolean("thinking", true)
        set(v) = prefs.edit().putBoolean("thinking", v).apply()

    /** Keep a copy of the chats on the phone too (off = chats are only on the PC). */
    var keepLocalCopy: Boolean
        get() = prefs.getBoolean("keep_local_copy", false)
        set(v) = prefs.edit().putBoolean("keep_local_copy", v).apply()

    /** Bridge address if it isn't on the AI server's PC at port 1235 (null = automatic). */
    var bridgeUrl: String?
        get() = prefs.getString("bridge_url", null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString("bridge_url", v).apply()

    /** Sampling used for every chat unless the chat has its own. */
    var defaultSampling: Sampling
        get() = prefs.getString("default_sampling", null)
            ?.let { runCatching { appJson.decodeFromString(Sampling.serializer(), it) }.getOrNull() } ?: Sampling()
        set(v) = prefs.edit().putString("default_sampling", appJson.encodeToString(Sampling.serializer(), v)).apply()

    /** A chat's own sampling settings (the chat file itself stays exactly as LM Studio writes it). */
    fun chatSampling(chatId: String): Sampling =
        prefs.getString("sampling_$chatId", null)
            ?.let { runCatching { appJson.decodeFromString(Sampling.serializer(), it) }.getOrNull() } ?: Sampling()

    fun setChatSampling(chatId: String, s: Sampling) {
        prefs.edit().putString("sampling_$chatId", appJson.encodeToString(Sampling.serializer(), s)).apply()
    }

    /** Load settings chosen on the phone for each model (context length, Flash Attention, ...). */
    fun loadConfig(modelKey: String): LoadConfig? =
        prefs.getString("load_$modelKey", null)?.let { runCatching { appJson.decodeFromString(LoadConfig.serializer(), it) }.getOrNull() }

    fun setLoadConfig(modelKey: String, cfg: LoadConfig) {
        prefs.edit().putString("load_$modelKey", appJson.encodeToString(LoadConfig.serializer(), cfg)).apply()
    }

    /** Plugins switched on for new chats (LM Studio plugin ids, e.g. danielsig/duckduckgo). */
    var defaultPlugins: List<String>
        get() = prefs.getString("default_plugins", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("default_plugins", v.joinToString(",")).apply()

    /** Unload other models before loading one (so two big models don't fight over the GPU). */
    var unloadOthersOnLoad: Boolean
        get() = prefs.getBoolean("unload_others", true)
        set(v) = prefs.edit().putBoolean("unload_others", v).apply()

    var terminalCwd: String
        get() = prefs.getString("terminal_cwd", "") ?: ""
        set(v) = prefs.edit().putString("terminal_cwd", v).apply()

    var terminalHistory: List<String>
        get() = prefs.getString("terminal_history", "").orEmpty().split('\u0001').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("terminal_history", v.takeLast(100).joinToString("\u0001")).apply()

    fun disconnectServer() {
        serverUrl = null
        serverKind = null
        apiKey = null
        modelId = null
    }
}
