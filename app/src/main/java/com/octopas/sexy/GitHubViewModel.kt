package com.octopas.sexy

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octopas.sexy.data.AiClient
import com.octopas.sexy.data.ChatMsg
import com.octopas.sexy.data.DiffLine
import com.octopas.sexy.data.EditParser
import com.octopas.sexy.data.FileEdit
import com.octopas.sexy.data.GhBranch
import com.octopas.sexy.data.GhPull
import com.octopas.sexy.data.GhRepo
import com.octopas.sexy.data.GhTreeEntry
import com.octopas.sexy.data.GitHubClient
import com.octopas.sexy.data.LineDiff
import com.octopas.sexy.data.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface GhStage {
    data object NeedToken : GhStage
    data object Repos : GhStage
    data object Repo : GhStage
    data object Running : GhStage
    data object Review : GhStage
    data class Done(val pr: GhPull, val branch: String, val base: String) : GhStage
}

class EditView(val edit: FileEdit, val isNew: Boolean, val lines: List<DiffLine>, val added: Int, val removed: Int)

/**
 * GitHub tab: pick a repo + files, let the local AI write changes, review the diff,
 * then commit to a NEW branch and open a pull request (the base branch is never touched).
 */
class GitHubViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as SexyApp).settings

    var stage by mutableStateOf<GhStage>(if (settings.githubToken != null) GhStage.Repos else GhStage.NeedToken)
        internal set
    var login by mutableStateOf(settings.githubLogin)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    // repo list
    var repos by mutableStateOf<List<GhRepo>>(emptyList())
        private set
    var repoQuery by mutableStateOf("")

    // one repo
    var repo by mutableStateOf<GhRepo?>(null)
        internal set
    var branches by mutableStateOf<List<GhBranch>>(emptyList())
        private set
    var branch by mutableStateOf("")
        internal set
    var files by mutableStateOf<List<GhTreeEntry>>(emptyList())
        internal set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var fileQuery by mutableStateOf("")
    var showSelectedOnly by mutableStateOf(false)
    var task by mutableStateOf("")
    val agentsFile: String? get() = files.firstOrNull { it.path.equals("AGENTS.md", true) }?.path

    // AI run
    var phase by mutableStateOf("")
        private set
    var output by mutableStateOf("")
        internal set
    var reasoning by mutableStateOf("")
        private set
    var stats by mutableStateOf<String?>(null)
        private set
    var summary by mutableStateOf("")
        internal set
    var edits by mutableStateOf<List<EditView>>(emptyList())
        internal set
    var expanded by mutableStateOf<Set<String>>(emptySet())
        internal set
    var revision by mutableStateOf("")
    var newBranch by mutableStateOf("")
    var prTitle by mutableStateOf("")
    var creating by mutableStateOf(false)
        private set

    private var originals: MutableMap<String, String> = mutableMapOf()
    private var conversation: MutableList<ChatMsg> = mutableListOf()
    private var runJob: Job? = null

    init {
        if (settings.githubToken != null) loadRepos()
    }

    private fun gh() = GitHubClient(settings.githubToken ?: "")

    private fun launchSafely(block: suspend () -> Unit) = viewModelScope.launch {
        loading = true
        error = null
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = friendlyError(e) } finally { loading = false }
    }

    // ---------- token ----------
    fun saveToken(token: String) = launchSafely {
        val t = token.trim()
        val user = GitHubClient(t).me()
        settings.githubToken = t
        settings.githubLogin = user.login
        login = user.login
        stage = GhStage.Repos
        repos = GitHubClient(t).repos()
    }

    fun signOut() {
        runJob?.cancel()
        settings.githubToken = null
        settings.githubLogin = null
        login = null
        repos = emptyList()
        repo = null
        stage = GhStage.NeedToken
    }

    // ---------- repos ----------
    fun loadRepos() = launchSafely { repos = gh().repos() }

    val filteredRepos: List<GhRepo>
        get() {
            val q = repoQuery.trim().lowercase()
            return if (q.isEmpty()) repos else repos.filter { q in it.fullName.lowercase() || q in (it.description ?: "").lowercase() }
        }

    fun openRepo(r: GhRepo) {
        repo = r
        branch = r.defaultBranch
        branches = emptyList()
        files = emptyList()
        selected = emptySet()
        fileQuery = ""
        showSelectedOnly = false
        stage = GhStage.Repo
        launchSafely {
            val client = gh()
            branches = client.branches(r.owner.login, r.name)
            loadTree(client)
        }
    }

    fun selectBranch(b: String) {
        if (b == branch) return
        branch = b
        selected = emptySet()
        launchSafely { loadTree(gh()) }
    }

    private suspend fun loadTree(client: GitHubClient) {
        val r = repo ?: return
        val tree = client.tree(r.owner.login, r.name, branch)
        files = tree.tree.filter { it.type == "blob" && isTextFile(it) }.sortedBy { it.path.lowercase() }
        if (tree.truncated) error = "This repo is very large - only part of the file list is shown."
    }

    private fun isTextFile(e: GhTreeEntry): Boolean {
        val ext = e.path.substringAfterLast('.', "").lowercase()
        if (ext in BINARY_EXT) return false
        if (e.path.contains("/build/") || e.path.startsWith("build/") || e.path.startsWith(".gradle/")) return false
        return (e.size ?: 0) <= 300_000
    }

    val visibleFiles: List<GhTreeEntry>
        get() {
            val words = fileQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return files.filter { f ->
                (!showSelectedOnly || f.path in selected) && words.all { it in f.path.lowercase() }
            }
        }

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun clearSelection() { selected = emptySet() }

    /** Rough token estimate (~3.5 characters per token for code). */
    val selectedTokens: Int
        get() {
            val paths = selected + listOfNotNull(agentsFile)
            val bytes = files.filter { it.path in paths }.sumOf { it.size ?: 0 }
            return (bytes / 3.5).toInt() + 1_500
        }

    // ---------- AI run ----------
    fun runAi() {
        val r = repo ?: return
        if (task.isBlank()) return
        if (selectedTokens > MAX_INPUT_TOKENS) {
            error = "Too much code selected (~${selectedTokens / 1000}k tokens). Your server has a 64k context - keep it under ${MAX_INPUT_TOKENS / 1000}k."
            return
        }
        error = null
        newBranch = ""
        prTitle = ""
        stage = GhStage.Running
        output = ""; reasoning = ""; stats = null
        phase = "Reading files from GitHub…"
        runJob = viewModelScope.launch {
            try {
                val client = gh()
                val paths = (listOfNotNull(agentsFile) + selected.sorted()).distinct()
                val contents = paths.map { p -> async { p to client.fileText(r.owner.login, r.name, p, branch) } }.awaitAll().toMap()
                originals = contents.toMutableMap()
                val user = buildString {
                    append("Repository: ${r.fullName} (branch $branch)\n\n")
                    if (contents.isEmpty()) append("(No files were selected - create new files if needed.)\n\n")
                    else append("FILES:\n").append(EditParser.formatFiles(contents))
                    append("TASK:\n").append(task.trim())
                }
                conversation = mutableListOf(ChatMsg("system", EDIT_SYSTEM_PROMPT.replace("{REPO}", r.fullName)), ChatMsg("user", user))
                streamAndReview(client)
            } catch (e: Throwable) {
                handleRunError(e)
            }
        }
    }

    fun revise() {
        val instruction = revision.trim()
        if (instruction.isEmpty() || conversation.isEmpty()) return
        conversation += ChatMsg("assistant", output)
        conversation += ChatMsg(
            "user",
            "Revise your changes: $instruction\n\nAnswer again in exactly the same format. Output the COMPLETE final " +
                "version of EVERY file you change (including files you already changed before), based on the original files I gave you."
        )
        revision = ""
        error = null
        stage = GhStage.Running
        output = ""; reasoning = ""; stats = null
        runJob = viewModelScope.launch {
            try { streamAndReview(gh()) } catch (e: Throwable) { handleRunError(e) }
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.streamAndReview(client: GitHubClient) {
        val r = repo ?: return
        phase = "Thinking…"
        val ai = AiClient(settings.serverUrl ?: "", settings.apiKey)
        ai.streamChat(settings.modelId ?: "default", conversation, thinking = true, maxTokens = 32_000).collect { ev ->
            when (ev) {
                is StreamEvent.Reasoning -> reasoning += ev.text
                is StreamEvent.Content -> {
                    output += ev.text
                    phase = if ("<<<FILE" in output || "<<<DELETE" in output) "Writing changes…" else "Answering…"
                }
                is StreamEvent.Stats -> stats = formatStats(ev)
            }
        }
        if (!isActive) return
        phase = "Preparing diff…"
        val parsed = EditParser.parse(output)
        val known = files.associateBy { it.path }
        val views = parsed.edits.map { e ->
            val existsInRepo = e.path in known
            val old = originals[e.path] ?: if (existsInRepo) {
                client.fileText(r.owner.login, r.name, e.path, branch).also { originals[e.path] = it }
            } else ""
            val lines = LineDiff.diff(old, e.newContent ?: "")
            val (a, d) = LineDiff.counts(lines)
            EditView(e, isNew = !existsInRepo, lines = LineDiff.compact(lines), added = a, removed = d)
        }.filter { it.edit.newContent == null || it.added + it.removed > 0 } // drop "changed" files that are identical
        summary = parsed.summary.ifBlank { if (views.isEmpty()) output.take(4000) else "" }
        edits = views
        expanded = views.take(3).map { it.edit.path }.toSet()
        if (views.isEmpty()) error = "The AI didn't return any file changes. Read its answer, then ask it to revise (or give it more files)."
        if (newBranch.isBlank()) newBranch = defaultBranchName()
        if (prTitle.isBlank()) prTitle = task.trim().lineSequence().first().take(70)
        stage = GhStage.Review
    }

    private fun handleRunError(e: Throwable) {
        if (e is CancellationException) {
            stage = GhStage.Repo
            return
        }
        error = friendlyError(e)
        stage = if (output.isNotEmpty()) GhStage.Review else GhStage.Repo
    }

    fun stopRun() {
        runJob?.cancel()
        stage = GhStage.Repo
    }

    fun toggleExpanded(path: String) {
        expanded = if (path in expanded) expanded - path else expanded + path
    }

    fun discard() {
        edits = emptyList()
        stage = GhStage.Repo
    }

    private fun defaultBranchName(): String {
        val slug = task.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).trim('-').ifBlank { "change" }
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
        return "ai/$slug-$stamp"
    }

    fun createPr() {
        val r = repo ?: return
        val br = newBranch.trim().replace(" ", "-")
        if (br.isEmpty() || edits.isEmpty()) return
        val modes = files.associate { it.path to it.mode }
        val model = settings.modelId ?: "local model"
        val body = buildString {
            append("## Task\n").append(task.trim()).append("\n\n")
            if (summary.isNotBlank()) append("## What the AI says it changed\n").append(summary.trim()).append("\n\n")
            append("## Files\n")
            edits.forEach { v ->
                val kind = when { v.edit.newContent == null -> "deleted"; v.isNew -> "new"; else -> "+${v.added} −${v.removed}" }
                append("- `").append(v.edit.path).append("` ($kind)\n")
            }
            append("\n---\nDrafted on my PC by the local model **").append(model)
            append("** using the SEXY app. ⚠️ AI-generated: review before merging.\n")
        }
        creating = true
        error = null
        viewModelScope.launch {
            try {
                val pr = gh().commitAndOpenPr(
                    owner = r.owner.login, repo = r.name, baseBranch = branch, newBranch = br,
                    commitMessage = "AI: ${task.trim().lineSequence().first().take(68)}\n\nGenerated by local $model via SEXY.",
                    prTitle = prTitle.ifBlank { task.take(70) }, prBody = body,
                    edits = edits.map { it.edit }, modes = modes,
                )
                stage = GhStage.Done(pr, br, branch)
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                creating = false
            }
        }
    }

    fun newTask() {
        task = ""
        selected = emptySet()
        edits = emptyList()
        output = ""; reasoning = ""; summary = ""
        newBranch = ""
        prTitle = ""
        stage = GhStage.Repo
    }

    fun back() {
        when (stage) {
            GhStage.Repo -> { stage = GhStage.Repos; repo = null }
            GhStage.Running -> stopRun()
            GhStage.Review -> discard()
            is GhStage.Done -> newTask()
            else -> Unit
        }
    }

    companion object {
        const val MAX_INPUT_TOKENS = 40_000

        val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "svgz", "jar", "aar", "apk", "aab", "so", "dex", "class",
            "keystore", "jks", "zip", "gz", "7z", "rar", "tar", "ttf", "otf", "woff", "woff2", "mp3", "wav", "ogg",
            "mp4", "mkv", "mov", "pdf", "gguf", "bin", "db", "sqlite", "lock",
        )

        val EDIT_SYSTEM_PROMPT = """
You are an expert software engineer working on the GitHub repository {REPO}, an app the user is building.
You get some of its files and a task. Rules:
- Make the smallest correct change that completes the task. Keep the existing style, architecture and language. Don't reformat unrelated code.
- If AGENTS.md is included, follow its rules.
- Never invent libraries, versions or APIs you are not sure exist. Don't change build files or dependency versions unless the task asks for it.
- If something is unclear or you need a file you weren't given, say so in the summary.

Answer format (strict):
1. First, 1-5 short bullet points: what you changed and anything the user must check.
2. Then EVERY changed or new file IN FULL (never partial, never "..." or "rest unchanged"), each exactly like:
<<<FILE path/from/repo/root>>>
the complete file content
<<<END>>>
3. To delete a file, write: <<<DELETE path/from/repo/root>>>
Do not output files you didn't change. Do not wrap file contents in ``` fences.
""".trim()
    }
}
