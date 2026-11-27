package com.octopas.sexy

import com.octopas.sexy.data.AiClient
import com.octopas.sexy.data.AiException
import com.octopas.sexy.data.AiModel
import com.octopas.sexy.data.ServerDiscovery
import com.octopas.sexy.data.ServerKind
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

    private fun client(key: String? = "secret", clock: () -> Long = System::nanoTime) =
        AiClient(server.url("/").toString().trimEnd('/'), key, clock = clock)

    /** Answers like a given kind of server; every other path is a 404, like the real thing. */
    private fun serve(routes: Map<String, String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
        }
    }

    @Test fun llamaServerModelsWithBearerKey() = runBlocking {
        serve(mapOf("/v1/models" to """{"object":"list","models":[{"name":"qwen3.6-35b","model":"qwen3.6-35b"}],"data":[{"id":"qwen3.6-35b","object":"model","owned_by":"llamacpp"}]}"""))
        val info = client().detect()
        assertEquals(ServerKind.LLAMA_CPP, info.kind)
        assertEquals(listOf("qwen3.6-35b"), info.models.map { it.id })
        val paths = generateSequence { server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
        assertTrue(paths.all { it.getHeader("Authorization") == "Bearer secret" })
        assertTrue(paths.any { it.path == "/v1/models" })
    }

    @Test fun lmStudioModelsLoadedFirstWithoutEmbeddings() = runBlocking {
        serve(
            mapOf(
                "/api/v0/models" to """{"object":"list","data":[
                  {"id":"text-embedding-nomic-embed-text-v1.5","type":"embeddings","state":"not-loaded"},
                  {"id":"qwen3.6-35b-a3b","type":"llm","state":"not-loaded"},
                  {"id":"gpt-oss-20b","type":"llm","state":"loaded"}]}"""
            )
        )
        val info = client(key = null).detect()
        assertEquals(ServerKind.LM_STUDIO, info.kind)
        assertEquals(listOf(AiModel("gpt-oss-20b", true), AiModel("qwen3.6-35b-a3b")), info.models)
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test fun ollamaWithLoadedModel() = runBlocking {
        serve(
            mapOf(
                "/api/tags" to """{"models":[
                  {"name":"llama3.2:3b","model":"llama3.2:3b","digest":"a","details":{"family":"llama"}},
                  {"name":"nomic-embed-text:latest","model":"nomic-embed-text:latest","digest":"b","details":{"family":"nomic-bert"}},
                  {"name":"qwen3:14b","model":"qwen3:14b","digest":"c","details":{"family":"qwen3"}}]}""",
                "/api/ps" to """{"models":[{"name":"qwen3:14b","model":"qwen3:14b"}]}""",
                "/api/version" to """{"version":"0.12.3"}""",
            )
        )
        val info = client(key = null).detect()
        assertEquals(ServerKind.OLLAMA, info.kind)
        assertEquals(listOf(AiModel("qwen3:14b", true), AiModel("llama3.2:3b")), info.models)
    }

    @Test fun ollamaLookalikeWithoutVersionIsNotOllama() = runBlocking {
        // llama.cpp's server copies /api/tags, but it isn't Ollama.
        serve(
            mapOf(
                "/api/tags" to """{"models":[{"name":"m","model":"m","digest":"x","details":{}}]}""",
                "/v1/models" to """{"data":[{"id":"m","owned_by":"llamacpp"}]}""",
            )
        )
        assertEquals(ServerKind.LLAMA_CPP, client(key = null).detect().kind)
    }

    @Test fun otherServersByOwner() = runBlocking {
        for ((owner, kind) in listOf("vllm" to ServerKind.VLLM, "koboldcpp" to ServerKind.KOBOLDCPP, "someone" to ServerKind.OPENAI_COMPATIBLE)) {
            serve(mapOf("/v1/models" to """{"data":[{"id":"m1","owned_by":"$owner"}]}"""))
            assertEquals(kind, client(key = null).detect().kind)
        }
    }

    @Test fun openWebUiUsesItsOwnApiPath() = runBlocking {
        serve(mapOf("/api/models" to """{"data":[{"id":"llama3","name":"Llama 3"}]}"""))
        val info = client().detect()
        assertEquals(ServerKind.OPEN_WEBUI, info.kind)
        assertEquals("/api/chat/completions", info.kind.chatPath)
        assertEquals(listOf("llama3"), info.models.map { it.id })
    }

    @Test fun webPageIsNotAnAiServer() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>hello</html>")
        }
        try { client().detect(); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("isn't an AI server")) }
    }

    @Test fun wrongKeyGivesFriendlyMessage() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API Key"}}""")
        }
        try { client("bad").listModels(); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("API key")) }
    }

    @Test fun serverDownBehindTailscaleServe() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(502)
        }
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

    @Test fun lmStudioStreamGetsSpeedWorkedOutAndThinkTagsSplit() = runBlocking {
        // LM Studio style: no "timings", thinking inline as <think> tags, usage at the end.
        val sse = listOf(
            """data: {"choices":[{"delta":{"content":"<think>hmm"}}]}""",
            """data: {"choices":[{"delta":{"content":"</think>Hi"}}]}""",
            """data: {"choices":[{"delta":{"content":"!"}}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":20}}""",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n\n")
        server.enqueue(MockResponse().setBody(sse))
        var t = 0L
        val fakeClock = { t.also { t += 500_000_000L } } // every reading is 0.5 s later
        val events = client(clock = fakeClock).streamChat("m", listOf(ChatMsg("user", "hi")), thinking = true).toList()
        assertEquals("hmm", events.filterIsInstance<StreamEvent.Reasoning>().joinToString("") { it.text })
        assertEquals("Hi!", events.filterIsInstance<StreamEvent.Content>().joinToString("") { it.text })
        val stats = events.last() as StreamEvent.Stats
        assertEquals(20, stats.completionTokens)
        assertEquals(40.0, stats.tokensPerSecond!!, 0.01) // 20 tokens in 0.5 s
    }
}

class DiscoveryTest {
    private val server = MockWebServer()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/api/v0/models" -> MockResponse().setBody("""{"data":[{"id":"qwen3.6-35b-a3b","type":"llm","state":"loaded"}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }
    @After fun stop() = server.shutdown()

    @Test fun findsThePcAmongDeadAddresses() = runBlocking {
        val good = server.url("/").toString().trimEnd('/')
        // Port 1 on localhost refuses connections straight away, like a PC without LM Studio running.
        val r = ServerDiscovery.find(listOf("http://127.0.0.1:1", good, "http://127.0.0.1:2"), apiKey = null)
        val found = (r as ServerDiscovery.Result.Success).found
        assertEquals(good, found.url)
        assertEquals(listOf(AiModel("qwen3.6-35b-a3b", loaded = true)), found.models)
    }

    @Test fun prefersEarlierAddressWhenBothAnswer() = runBlocking {
        val other = MockWebServer()
        other.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/v1/models" -> MockResponse().setBody("""{"data":[{"id":"x","owned_by":"vllm"}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        other.start()
        val lmStudio = server.url("/").toString().trimEnd('/')
        val vllm = other.url("/").toString().trimEnd('/')
        // LM Studio is listed first, so it wins even if vLLM happens to answer sooner.
        val r = ServerDiscovery.find(listOf(lmStudio, vllm), apiKey = null)
        assertEquals(ServerKind.LM_STUDIO, (r as ServerDiscovery.Result.Success).found.kind)
        other.shutdown()
    }

    @Test fun nothingAnswering() = runBlocking {
        val r = ServerDiscovery.find(listOf("http://127.0.0.1:1", "http://127.0.0.1:2"), apiKey = null)
        assertTrue(r is ServerDiscovery.Result.Failed)
        assertEquals(null, (r as ServerDiscovery.Result.Failed).refused)
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
