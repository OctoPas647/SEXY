package com.octopas.sexy

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octopas.sexy.data.BridgeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class TerminalEntry(val cwd: String, val command: String, val output: String? = null, val exitCode: Int? = null, val running: Boolean = false)

/** A PowerShell terminal on the PC, through the bridge (Tailscale only). */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as SexyApp).settings

    val entries = mutableStateListOf<TerminalEntry>()
    var cwd by mutableStateOf(settings.terminalCwd)
        internal set
    var running by mutableStateOf(false)
        private set
    /** Seconds a command may run before it's stopped. */
    var timeoutSec by mutableStateOf(120)

    private var history = settings.terminalHistory
    private var historyIndex = history.size
    private var job: Job? = null

    private fun bridge() = (settings.bridgeUrl ?: BridgeClient.urlFor(settings.serverUrl))?.let { BridgeClient(it) }

    fun run(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        if (cmd == "clear" || cmd == "cls") { entries.clear(); return }
        history = (history.filter { it != cmd } + cmd).takeLast(100)
        settings.terminalHistory = history
        historyIndex = history.size
        val b = bridge()
        if (b == null) {
            entries += TerminalEntry(cwd, cmd, "Not connected to a PC.", 1)
            return
        }
        entries += TerminalEntry(cwd.ifBlank { "~" }, cmd, running = true)
        val i = entries.lastIndex
        running = true
        job = viewModelScope.launch {
            try {
                val r = b.run(cmd, cwd, timeoutSec)
                entries[i] = entries[i].copy(
                    output = r.output + if (r.timedOut) "\n[stopped after ${timeoutSec}s]" else "",
                    exitCode = r.exitCode, running = false,
                )
                if (r.cwd.isNotBlank()) { cwd = r.cwd; settings.terminalCwd = r.cwd }
            } catch (e: Exception) {
                entries[i] = entries[i].copy(output = friendlyError(e), exitCode = 1, running = false)
            } finally {
                running = false
            }
        }
    }

    /** Up arrow: previous command. */
    fun previous(): String? {
        if (history.isEmpty()) return null
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        return history[historyIndex]
    }

    /** Down arrow: next command (or empty at the end). */
    fun next(): String {
        historyIndex = (historyIndex + 1).coerceAtMost(history.size)
        return history.getOrNull(historyIndex).orEmpty()
    }

    fun clear() = entries.clear()

    fun cancel() { job?.cancel(); running = false }
}
