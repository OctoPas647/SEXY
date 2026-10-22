package com.octopas.sexy

import com.octopas.sexy.data.AiClient
import com.octopas.sexy.data.AiException
import com.octopas.sexy.data.ChatMsg
import com.octopas.sexy.data.FileEdit
import com.octopas.sexy.data.GitHubClient
import com.octopas.sexy.data.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AiClientTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun client(key: String? = "secret") = AiClient(server.url("/").toString().trimEnd('/'), key)

    @Test fun listsModelsWithBearerKey() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"qwen3.6-35b","object":"model"}]}"""))
        assertEquals(listOf("qwen3.6-35b"), client().listModels())
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer secret", req.getHeader("Authorization"))
    }

    @Test fun wrongKeyGivesFriendlyMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API Key"}}"""))
        try { client("bad").listModels(); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("Wrong API key")) }
    }

    @Test fun serverDownBehindTailscaleServe() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))
        try { client().listModels(); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("isn't running")) }
    }

    @Test fun streamsReasoningContentAndStats() = runBlocking {
        val sse = listOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":null}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"Let me think"}}]}""",
            """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
            """data: {"choices":[{"delta":{"content":" world"}}]}""",
            """data: {"choices":[{"finish_reason":"stop","delta":{}}],"usage":{"prompt_tokens":5,"completion_tokens":3},"timings":{"predicted_per_second":61.2}}""",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n\n")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))

        val events = client().streamChat("qwen3.6-35b", listOf(ChatMsg("user", "hi")), thinking = false).toList()
        assertEquals(
            listOf(
                StreamEvent.Reasoning("Let me think"),
                StreamEvent.Content("Hello"),
                StreamEvent.Content(" world"),
                StreamEvent.Stats(61.2, 5, 3),
            ),
            events,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"enable_thinking\":false"))
        assertTrue(body.contains("\"model\":\"qwen3.6-35b\""))
    }
}

class GitHubClientTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<Pair<String, String>>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                synchronized(requests) { requests += "${request.method} $path" to body }
                return when {
                    request.method == "GET" && path == "/repos/me/bingo/git/ref/heads/main" ->
                        json("""{"ref":"refs/heads/main","object":{"sha":"base123","type":"commit"}}""")
                    request.method == "GET" && path == "/repos/me/bingo/git/commits/base123" ->
                        json("""{"sha":"base123","tree":{"sha":"tree123"}}""")
                    request.method == "POST" && path == "/repos/me/bingo/git/trees" -> json("""{"sha":"newtree"}""")
                    request.method == "POST" && path == "/repos/me/bingo/git/commits" -> json("""{"sha":"newcommit"}""")
                    request.method == "POST" && path == "/repos/me/bingo/git/refs" -> json("""{"ref":"refs/heads/ai/x"}""")
                    request.method == "POST" && path == "/repos/me/bingo/pulls" && body.contains("\"draft\":true") ->
                        MockResponse().setResponseCode(422).setBody("""{"message":"Draft pull requests are not supported in this repository."}""")
                    request.method == "POST" && path == "/repos/me/bingo/pulls" ->
                        json("""{"number":7,"html_url":"https://github.com/me/bingo/pull/7","draft":false}""")
                    request.method == "GET" && path.startsWith("/repos/me/bingo/contents/app/src/My%20File.kt") ->
                        MockResponse().setBody("class My\n")
                    else -> MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
    private fun gh() = GitHubClient("tok", server.url("/").toString().trimEnd('/'))

    @Test fun commitsToNewBranchAndFallsBackFromDraftPr() = runBlocking {
        val pr = gh().commitAndOpenPr(
            owner = "me", repo = "bingo", baseBranch = "main", newBranch = "ai/x",
            commitMessage = "AI: test", prTitle = "Test", prBody = "body",
            edits = listOf(FileEdit("a.kt", "val a = 1\n"), FileEdit("old.kt", null)),
            modes = mapOf("a.kt" to "100755"),
        )
        assertEquals(7, pr.number)
        val tree = requests.first { it.first == "POST /repos/me/bingo/git/trees" }.second
        assertTrue(tree, tree.contains("\"base_tree\":\"tree123\""))
        assertTrue(tree, tree.contains("\"mode\":\"100755\""))
        assertTrue(tree, tree.contains("\"sha\":null"))
        val commit = requests.first { it.first == "POST /repos/me/bingo/git/commits" }.second
        assertTrue(commit, commit.contains("\"parents\":[\"base123\"]"))
        val ref = requests.first { it.first == "POST /repos/me/bingo/git/refs" }.second
        assertTrue(ref, ref.contains("refs/heads/ai/x") && ref.contains("newcommit"))
        // main was never written to
        assertTrue(requests.none { it.first.contains("PATCH") })
    }

    @Test fun readsFileWithSpacesInPath() = runBlocking {
        assertEquals("class My\n", gh().fileText("me", "bingo", "app/src/My File.kt", "main"))
    }
}
