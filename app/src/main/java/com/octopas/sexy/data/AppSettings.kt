package com.octopas.sexy.data

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(v) = prefs.edit().putString("server_url", v).apply()

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

    var thinking: Boolean
        get() = prefs.getBoolean("thinking", true)
        set(v) = prefs.edit().putBoolean("thinking", v).apply()

    fun disconnectServer() {
        serverUrl = null
        apiKey = null
        modelId = null
    }
}
