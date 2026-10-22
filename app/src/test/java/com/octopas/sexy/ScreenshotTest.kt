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
import com.octopas.sexy.ui.ChatScreen
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
            .putString("server_url", "http://desktop-d0efhda:3030")
            .putString("model_id", "qwen3.6-35b")
            .commit()
    }

    @Test fun connect() = shot("1-connect") { SexyRoot(AppViewModel(app)) }

    @Test fun chatEmpty() {
        pretendConnected()
        shot("2-chat-empty") { ChatScreen(AppViewModel(app), ChatViewModel(app)) }
    }

    @Test fun chatConversation() {
        pretendConnected()
        val chat = ChatViewModel(app)
        chat.messages += UiMessage("user", "How do I save a setting in my Android app?")
        chat.messages += UiMessage(
            "assistant",
            content = """
The simplest way is **SharedPreferences**:

```kotlin
val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)
prefs.edit().putBoolean("dark_mode", true).apply()
```

- Use `getBoolean("dark_mode", false)` to read it back
- For bigger data, use **DataStore** or Room
""".trim(),
            reasoning = "The user wants to persist a simple setting. SharedPreferences is the easiest option for a beginner...",
            stats = "58.3 tok/s · 212 tokens",
        )
        shot("3-chat") { ChatScreen(AppViewModel(app), chat) }
    }

    @Test fun githubToken() {
        pretendConnected()
        shot("4-github-token") { GitHubScreen(GitHubViewModel(app)) }
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
        shot("6-github-review") { GitHubScreen(gh) }
    }

    @Test fun settings() {
        pretendConnected()
        shot("5-settings") { SettingsScreen(AppViewModel(app), ChatViewModel(app), GitHubViewModel(app), onOpenGitHub = {}) }
    }
}
