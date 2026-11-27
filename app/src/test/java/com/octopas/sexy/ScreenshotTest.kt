package com.octopas.sexy

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.octopas.sexy.data.Attachment
import com.octopas.sexy.data.ChatMessage
import com.octopas.sexy.data.ChatSummary
import com.octopas.sexy.data.GenStats
import com.octopas.sexy.data.LmModel
import com.octopas.sexy.data.LoadedInstance
import com.octopas.sexy.data.MsgVersion
import com.octopas.sexy.data.PluginInfo
import com.octopas.sexy.data.ToolUse
import com.octopas.sexy.ui.ChatListScreen
import com.octopas.sexy.ui.ChatScreen
import com.octopas.sexy.ui.ModelsScreen
import com.octopas.sexy.ui.TerminalScreen
import com.octopas.sexy.ui.GitHubScreen
import com.octopas.sexy.ui.SettingsScreen
import com.octopas.sexy.ui.SexyRoot
import com.octopas.sexy.ui.theme.SexyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real screens to PNGs in app/screenshots/ so they can be checked without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-night-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val app: SexyApp get() = ApplicationProvider.getApplicationContext()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            SexyTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun pretendConnected() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("server_url", "http://desktop-d0efhda:1234")
            .putString("server_kind", "LM_STUDIO")
            .putString("model_id", "qwen/qwen3.6-35b-a3b")
            .commit()
    }

    private val now = System.currentTimeMillis()

    private fun sampleChats() = listOf(
        ChatSummary("1", name = "Dark mode for BingO", pinned = true, createdAt = now - 3_600_000, updatedAt = now - 600_000, tokenCount = 4210, model = "qwen3.6-35b-a3b"),
        ChatSummary("2", name = "Thermal paste for the TUF F15", createdAt = now - 7_200_000, updatedAt = now - 5_000_000, tokenCount = 1250, model = "gemma-4-12b"),
        ChatSummary("3", name = "IELTS writing task 2 feedback", createdAt = now - 90_000_000, updatedAt = now - 88_000_000, tokenCount = 3400, model = "qwen3.6-35b-a3b"),
        ChatSummary("4", name = "Kyokushin training plan", createdAt = now - 4 * 86_400_000L, updatedAt = now - 4 * 86_400_000L, model = "qwen3-14b"),
        ChatSummary("5", folder = "Work", name = "Arduino breadboard power", createdAt = now - 20 * 86_400_000L, updatedAt = now - 20 * 86_400_000L, model = "gpt-oss-20b"),
    )

    private fun sampleModels() = listOf(
        LmModel("qwen/qwen3.6-35b-a3b", "Qwen3.6 35B A3B", "llm", architecture = "qwen3moe", quantization = "Q4_K_M", sizeBytes = 21_500_000_000, params = "35B",
            maxContext = 262144, tools = true, reasoningOptions = listOf("off", "on"), loaded = listOf(LoadedInstance("qwen/qwen3.6-35b-a3b", 16384, true, true))),
        LmModel("google/gemma-4-12b", "Gemma 4 12B", "llm", quantization = "Q6_K", sizeBytes = 10_100_000_000, params = "12B", vision = true, maxContext = 262144),
        LmModel("qwen/qwen3.8-27b", "Qwen3.8 27B", "llm", quantization = "IQ4_XS", sizeBytes = 15_700_000_000, params = "27B", vision = true, tools = true, maxContext = 262144),
        LmModel("openai/gpt-oss-20b", "gpt-oss 20B", "llm", quantization = "MXFP4", sizeBytes = 12_800_000_000, params = "20B", tools = true, maxContext = 131072),
        LmModel("text-embedding-nomic-embed-text-v1.5", "Nomic Embed Text v1.5", "embedding", quantization = "Q4_K_M", sizeBytes = 84_000_000),
    )

    @Test fun connect() = shot("1-connect") { SexyRoot(AppViewModel(app, autoSearch = false)) }

    @Test fun chatList() {
        pretendConnected()
        val chat = ChatViewModel(app)
        chat.chats = sampleChats()
        shot("2-chats") { ChatListScreen(AppViewModel(app, autoSearch = false), chat) }
    }

    @Test fun rootWithGlassTabBar() {
        pretendConnected()
        shot("0-home") { SexyRoot(AppViewModel(app, autoSearch = false)) }
    }

    @Test fun chatConversation() {
        pretendConnected()
        val chat = ChatViewModel(app)
        chat.isOpen = true
        chat.doc = chat.doc.copy(name = "Dark mode for BingO", plugins = listOf("danielsig/duckduckgo"))
        chat.messages += ChatMessage(listOf(MsgVersion(
            "user", "How do I save a setting in my Android app?",
            attachments = listOf(Attachment("SettingsScreen.kt", false, "20261127-130512-SettingsScreen.kt")),
        )))
        chat.messages += ChatMessage(listOf(
            MsgVersion("assistant", "First try."),
            MsgVersion(
                "assistant",
                """
The simplest way is **SharedPreferences**:

```kotlin
val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)
prefs.edit().putBoolean("dark_mode", true).apply()
```

- Use `getBoolean("dark_mode", false)` to read it back
- For bigger data, use **DataStore** or Room
""".trim(),
                reasoning = "The user wants to persist a simple setting. SharedPreferences is the easiest option...",
                tools = listOf(ToolUse("danielsig/duckduckgo: Web Search {\"query\":\"android save setting\"}", "toolCallSucceeded")),
                stats = GenStats(tokensPerSecond = 38.4, timeToFirstTokenSec = 0.62, predictedTokens = 212),
                model = "qwen/qwen3.6-35b-a3b",
            ),
        ), selected = 1)
        val models = ModelsViewModel(app)
        shot("3-chat") { ChatScreen(AppViewModel(app, autoSearch = false), chat, models) }
    }

    @Test fun modelsScreen() {
        pretendConnected()
        val vm = ModelsViewModel(app)
        vm.models = sampleModels()
        shot("4-models") { ModelsScreen(AppViewModel(app, autoSearch = false), vm, onUseInChat = {}) }
    }

    @Test fun terminal() {
        pretendConnected()
        val vm = TerminalViewModel(app)
        vm.cwd = "C:\\Users\\admin"
        vm.entries += TerminalEntry("C:\\Users\\admin", "nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv",
            "name, memory.used [MiB], memory.total [MiB]\nNVIDIA GeForce RTX 5080, 14230 MiB, 16303 MiB", 0)
        vm.entries += TerminalEntry("C:\\Users\\admin", "lms ps", "LOADED MODELS\n\nIdentifier: qwen/qwen3.6-35b-a3b\n  • Type:  LLM\n  • Context: 16384", 0)
        vm.entries += TerminalEntry("C:\\Users\\admin", "Get-Item D:\\missing", "Get-Item : Cannot find path 'D:\\missing' because it does not exist.", 1)
        shot("5-terminal") { TerminalScreen(AppViewModel(app, autoSearch = false), vm) }
    }

    @Test fun githubToken() {
        pretendConnected()
        shot("7-github-token") { GitHubScreen(GitHubViewModel(app)) }
    }

    @Test fun githubReview() {
        pretendConnected()
        val gh = GitHubViewModel(app)
        val old = "package bingo\n\nclass SettingsScreen {\n    fun render() {\n        title()\n    }\n}\n"
        val new = "package bingo\n\nclass SettingsScreen {\n    fun render() {\n        title()\n        darkModeSwitch()\n    }\n\n    fun darkModeSwitch() = Switch(prefs.darkMode)\n}\n"
        val lines = com.octopas.sexy.data.LineDiff.diff(old, new)
        val (a, d) = com.octopas.sexy.data.LineDiff.counts(lines)
        gh.repo = com.octopas.sexy.data.GhRepo("BingO", "OctoPas647/BingO", true, "main", null, null, "https://github.com/OctoPas647/BingO", com.octopas.sexy.data.GhOwner("OctoPas647"))
        gh.branch = "main"
        gh.task = "Add a dark-mode switch to the settings screen"
        gh.summary = "- Added `darkModeSwitch()` to SettingsScreen\n- Saved in prefs - check the key name"
        gh.edits = listOf(EditView(com.octopas.sexy.data.FileEdit("app/src/main/java/bingo/SettingsScreen.kt", new), false, com.octopas.sexy.data.LineDiff.compact(lines), a, d))
        gh.expanded = setOf("app/src/main/java/bingo/SettingsScreen.kt")
        gh.newBranch = "ai/add-a-dark-mode-switch-1022-1330"
        gh.prTitle = "Add a dark-mode switch to the settings screen"
        gh.stage = GhStage.Review
        shot("8-github-review") { GitHubScreen(gh) }
    }

    @Test fun settings() {
        pretendConnected()
        val models = ModelsViewModel(app)
        models.plugins = listOf(PluginInfo("danielsig/duckduckgo", "duckduckgo"), PluginInfo("lmstudio/wikipedia", "wikipedia"), PluginInfo("lmstudio/js-code-sandbox", "js-code-sandbox"))
        shot("6-settings") { SettingsScreen(AppViewModel(app, autoSearch = false), ChatViewModel(app), models) }
    }
}
