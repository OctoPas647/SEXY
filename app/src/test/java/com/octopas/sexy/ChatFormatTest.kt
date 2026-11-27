package com.octopas.sexy

import com.octopas.sexy.data.Attachment
import com.octopas.sexy.data.BridgeClient
import com.octopas.sexy.data.ChatConflict
import com.octopas.sexy.data.ChatMerge
import com.octopas.sexy.data.ChatMessage
import com.octopas.sexy.data.GenStats
import com.octopas.sexy.data.LmStudioApi
import com.octopas.sexy.data.LmsFormat
import com.octopas.sexy.data.MsgVersion
import com.octopas.sexy.data.NativeEvent
import com.octopas.sexy.data.NativeSse
import com.octopas.sexy.data.Reasoning
import com.octopas.sexy.data.Sampling
import com.octopas.sexy.data.ToolUse
import com.octopas.sexy.data.appJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A chat shaped exactly like the files LM Studio 0.4 writes (fields and nesting copied from a real one). */
private val LMS_CHAT = """
{"name":"Ghost in the shell","pinned":false,"createdAt":1795702727607,"preset":"","tokenCount":4993,
 "userLastMessagedAt":1795702740000,"assistantLastMessagedAt":1795702783158,"systemPrompt":"Be brief.",
 "messages":[
  {"versions":[{"type":"singleStep","role":"user","content":[{"type":"text","text":"What is 2+2?"}]}],"currentlySelected":0},
  {"versions":[{"type":"multiStep","role":"assistant","senderInfo":{"senderName":"huihui-qwen3.6-35b-a3b-abliterated"},"steps":[
     {"type":"contentBlock","stepIdentifier":"1-0.1","content":[{"type":"text","text":"Simple sum.","fromDraftModel":false,"tokensCount":3,"isStructural":false}],
      "defaultShouldIncludeInContext":true,"shouldIncludeInContext":true,"prefix":"","suffix":"__LM_STUDIO_INTERNAL__",
      "style":{"type":"thinking","ended":true,"title":"Thought for 1.20 seconds","forceRenderAsPlainText":false}},
     {"type":"toolStatus","stepIdentifier":"2-0.2","callId":2021015604750031,"statusState":{"status":{"type":"toolCallSucceeded","timeMs":4550},"customStatus":"Initiating DuckDuckGo web search...","customWarnings":[]}},
     {"type":"contentBlock","stepIdentifier":"3-0.3","content":[{"type":"text","text":"It's **4**.","fromDraftModel":false,"tokensCount":5,"isStructural":false}],
      "defaultShouldIncludeInContext":true,"shouldIncludeInContext":true,"prefix":"","suffix":"",
      "genInfo":{"indexedModelIdentifier":"huihui-ai/x.gguf","identifier":"huihui-qwen3.6-35b-a3b-abliterated","predictionConfig":{"fields":[]},
       "stats":{"stopReason":"eosFound","tokensPerSecond":37.6,"timeToFirstTokenSec":0.48,"totalTimeSec":2.1,"promptTokensCount":20,"predictedTokensCount":8}}},
     {"type":"debugInfoBlock","stepIdentifier":"4-0.4","debugInfo":"Conversation naming technique: 'prompt'"}]}],
   "currentlySelected":0}],
 "usePerChatPredictionConfig":true,"perChatPredictionConfig":{"fields":[]},"clientInput":"","clientInputFiles":[],"userFilesSizeBytes":0,
 "lastUsedModel":{"identifier":"huihui-qwen3.6-35b-a3b-abliterated","indexedModelIdentifier":"huihui-ai/x.gguf","instanceLoadTimeConfig":{"fields":[{"key":"llm.load.contextLength","value":8192}]},"instanceOperationTimeConfig":{"fields":[]}},
 "notes":[],"plugins":["lmstudio/wikipedia","danielsig/duckduckgo"],"pluginConfigs":{},"disabledPluginTools":[],"looseFiles":[],"workingDirectoryName":"1795705292437"}
""".trimIndent()

class LmsFormatTest {
    @Test fun readsLmStudioChat() {
        val d = LmsFormat.parse("1795702727607", LMS_CHAT, updatedAt = 99)
        assertEquals("Ghost in the shell", d.title)
        assertEquals("Be brief.", d.systemPrompt)
        assertEquals(listOf("lmstudio/wikipedia", "danielsig/duckduckgo"), d.plugins)
        assertEquals("huihui-qwen3.6-35b-a3b-abliterated", d.lastModel)
        assertEquals(2, d.messages.size)
        assertEquals("What is 2+2?", d.messages[0].current.text)
        val a = d.messages[1].current
        assertEquals("It's **4**.", a.text)
        assertEquals("Simple sum.", a.reasoning)
        assertEquals("Initiating DuckDuckGo web search...", a.tools.single().name)
        assertEquals(37.6, a.stats!!.tokensPerSecond!!, 0.001)
        assertEquals(99, d.updatedAt)
    }

    @Test fun savingUntouchedChatKeepsEveryLmStudioField() {
        val d = LmsFormat.parse("1795702727607", LMS_CHAT)
        val out = appJson.parseToJsonElement(LmsFormat.encode(d)).jsonObject
        val orig = appJson.parseToJsonElement(LMS_CHAT).jsonObject
        // Messages are written back byte-for-byte equal (the raw JSON is kept).
        assertEquals(orig["messages"], out["messages"])
        for (k in listOf("preset", "perChatPredictionConfig", "lastUsedModel", "pluginConfigs", "workingDirectoryName", "looseFiles")) {
            assertEquals("field $k", orig[k], out[k])
        }
    }

    @Test fun newAnswerIsWrittenInLmStudioShape() {
        val d = LmsFormat.parse("1795702727607", LMS_CHAT)
        val reply = MsgVersion(
            "assistant", "Five.", reasoning = "Hmm", tools = listOf(ToolUse("danielsig/duckduckgo: Web Search", "toolCallSucceeded")),
            stats = GenStats(tokensPerSecond = 40.0, predictedTokens = 2), model = "qwen3-14b",
        )
        val d2 = d.copy(messages = d.messages + ChatMessage(listOf(MsgVersion("user", "And 2+3?"))) + ChatMessage(listOf(reply)))
        val out = appJson.parseToJsonElement(LmsFormat.encode(d2)).jsonObject
        val msgs = out["messages"]!!.jsonArray
        assertEquals(4, msgs.size)
        val user = msgs[2].jsonObject["versions"]!!.jsonArray[0].jsonObject
        assertEquals("singleStep", user["type"]!!.jsonPrimitive.content)
        val asst = msgs[3].jsonObject["versions"]!!.jsonArray[0].jsonObject
        assertEquals("multiStep", asst["type"]!!.jsonPrimitive.content)
        val steps = asst["steps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("contentBlock", "toolStatus", "contentBlock"), steps.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("thinking", steps[0]["style"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // Reading our own file back gives the same message.
        val back = LmsFormat.parse("x", out.toString()).messages[3].current
        assertEquals("Five.", back.text)
        assertEquals("Hmm", back.reasoning)
        assertEquals("qwen3-14b", back.model)
        assertEquals(40.0, back.stats!!.tokensPerSecond!!, 0.001)
    }

    @Test fun brandNewChatHasAllLmStudioFields() {
        val d = LmsFormat.empty("1800000000000", systemPrompt = "Hi").copy(
            messages = listOf(ChatMessage(listOf(MsgVersion("user", "hello")))), name = "hello",
        )
        val out = appJson.parseToJsonElement(LmsFormat.encode(d)).jsonObject
        for (k in listOf("name", "pinned", "createdAt", "preset", "tokenCount", "systemPrompt", "messages", "usePerChatPredictionConfig",
            "perChatPredictionConfig", "clientInput", "clientInputFiles", "userFilesSizeBytes", "notes", "plugins", "pluginConfigs",
            "disabledPluginTools", "looseFiles")) {
            assertTrue("missing $k", out.containsKey(k))
        }
        assertEquals(1800000000000, out["createdAt"]!!.jsonPrimitive.content.toLong())
    }

    @Test fun versionsAndSelectionSurvive() {
        val m = ChatMessage(listOf(MsgVersion("assistant", "one", model = "m"), MsgVersion("assistant", "two", model = "m")), selected = 0)
        val d = LmsFormat.empty("1").copy(messages = listOf(ChatMessage(listOf(MsgVersion("user", "q"))), m))
        val back = LmsFormat.parse("1", LmsFormat.encode(d)).messages[1]
        assertEquals(2, back.versions.size)
        assertEquals(0, back.selected)
        assertEquals("one", back.current.text)
    }

    @Test fun attachmentsAndFileTextRoundTrip() {
        val atts = listOf(
            Attachment("report.pdf", false, pcName = "20261127-130512-report.pdf", extractedText = "page text"),
            Attachment("cat.jpg", true, pcName = "20261127-130513-cat.jpg"),
        )
        val v = MsgVersion("user", "Summarise this", attachments = atts, preprocessed = "Summarise this\n\n<file name=\"report.pdf\">\npage text\n</file>")
        val d = LmsFormat.empty("1").copy(messages = listOf(ChatMessage(listOf(v))))
        val back = LmsFormat.parse("1", LmsFormat.encode(d)).messages[0].current
        assertEquals("Summarise this", back.text)
        assertEquals(listOf("report.pdf", "cat.jpg"), back.attachments.map { it.name })
        assertEquals(listOf(false, true), back.attachments.map { it.isImage })
        assertTrue(back.contextText.contains("page text"))
    }

    @Test fun splitAttachmentsLeavesNormalTextAlone() {
        assertEquals("just text" to emptyList<Attachment>(), LmsFormat.splitAttachments("just text"))
    }
}

class ChatMergeTest {
    private fun doc(vararg texts: String) = LmsFormat.empty("1").copy(
        messages = texts.mapIndexed { i, t -> ChatMessage(listOf(MsgVersion(if (i % 2 == 0) "user" else "assistant", t))) },
    )

    @Test fun phoneContinuedThePcChat() {
        val pc = doc("a", "b").copy(name = "Renamed on PC", updatedAt = 10)
        val phone = doc("a", "b", "c", "d")
        val m = ChatMerge.merge(phone, pc)
        assertEquals("1", m.id)
        assertEquals(4, m.messages.size)
        assertEquals("Renamed on PC", m.name)
    }

    @Test fun pcMovedOnAndPhoneAddedNothing() {
        val m = ChatMerge.merge(doc("a", "b"), doc("a", "b", "c", "d"))
        assertEquals(4, m.messages.size)
        assertEquals("1", m.id)
    }

    @Test fun bothAddedDifferentThingsKeepsBoth() {
        val m = ChatMerge.merge(doc("a", "b", "phone"), doc("a", "b", "pc"))
        assertNotEquals("1", m.id)
        assertTrue(m.name!!.endsWith("(phone)"))
        assertEquals("phone", m.messages.last().current.text)
    }
}

class NativeSseTest {
    @Test fun parsesLmStudioChatEvents() {
        assertEquals(NativeEvent.ModelLoad(0.5f), NativeSse.parse("""data: {"type":"model_load.progress","model_instance_id":"m","progress":0.5}"""))
        assertEquals(NativeEvent.Message("Hi"), NativeSse.parse("""data: {"type":"message.delta","content":"Hi"}"""))
        assertEquals(NativeEvent.Reasoning("hm"), NativeSse.parse("""data: {"type":"reasoning.delta","content":"hm"}"""))
        val tool = NativeSse.parse("""data: {"type":"tool_call.success","tool":"search","arguments":{"q":"x"},"output":"ok","provider_info":{"type":"plugin","plugin_id":"danielsig/duckduckgo"}}""")
        assertEquals(NativeEvent.ToolDone("search", """{"q":"x"}""", "ok", "danielsig/duckduckgo"), tool)
        val end = NativeSse.parse("""data: {"type":"chat.end","result":{"output":[],"stats":{"tokens_per_second":42.5,"total_output_tokens":10,"input_tokens":5,"time_to_first_token_seconds":0.3},"response_id":"resp_abc"}}""") as NativeEvent.End
        assertEquals("resp_abc", end.responseId)
        assertEquals(42.5, end.stats!!.tokensPerSecond!!, 0.001)
        assertNull(NativeSse.parse("event: message.delta"))
    }

    @Test fun errorEventThrows() {
        try {
            NativeSse.parse("""data: {"type":"error","error":{"type":"model_not_found","message":"No such model"}}""")
            fail()
        } catch (e: com.octopas.sexy.data.AiException) {
            assertEquals("No such model", e.message)
        }
    }
}

class LmStudioModelsTest {
    @Test fun parsesV1ModelList() {
        val json = """{"models":[
          {"type":"llm","publisher":"google","key":"google/gemma-4-26b-a4b","display_name":"Gemma 4 26B A4B","architecture":"gemma4",
           "quantization":{"name":"Q4_K_M","bits_per_weight":4.8},"size_bytes":15000000000,"params_string":"26B","loaded_instances":[],
           "max_context_length":262144,"format":"gguf","capabilities":{"vision":true,"trained_for_tool_use":true,"reasoning":{"allowed_options":["off","on"],"default":"on"}}},
          {"type":"llm","publisher":"qwen","key":"qwen/qwen3-14b","display_name":"Qwen3 14B","quantization":null,"size_bytes":9000000000,"params_string":"14B",
           "loaded_instances":[{"id":"qwen/qwen3-14b","config":{"context_length":16384,"flash_attention":true,"offload_kv_cache_to_gpu":true}}],"max_context_length":40960,"format":"gguf"},
          {"type":"embedding","publisher":"nomic","key":"text-embedding-nomic","display_name":"Nomic Embed","quantization":null,"size_bytes":80000000,"params_string":null,"loaded_instances":[],"max_context_length":2048,"format":"gguf"}]}"""
        val list = LmStudioApi.parseModels(appJson.parseToJsonElement(json))
        assertEquals("qwen/qwen3-14b", list.first().key) // loaded first
        assertEquals(16384, list.first().loaded.single().contextLength)
        val gemma = list.first { it.key.startsWith("google") }
        assertTrue(gemma.vision && gemma.tools && gemma.isMoe)
        assertEquals(listOf("off", "on"), gemma.reasoningOptions)
        assertFalse(list.first { it.type == "embedding" }.isChat)
    }

    @Test fun perChatSamplingOverridesDefaults() {
        val s = Sampling(temperature = 0.2).over(Sampling(temperature = 0.8, topK = 40, reasoning = Reasoning.HIGH))
        assertEquals(0.2, s.temperature!!, 0.0)
        assertEquals(40, s.topK)
        assertEquals(Reasoning.HIGH, s.reasoning)
    }
}

class BridgeClientTest {
    @Test fun bridgeIsOnTheSamePcOnPort1235() {
        assertEquals("http://desktop-d0efhda:1235", BridgeClient.urlFor("http://desktop-d0efhda:1234"))
        assertEquals("http://100.1.2.3:1235", BridgeClient.urlFor("http://100.1.2.3:11434"))
        assertNull(BridgeClient.urlFor(null))
    }

    @Test fun listGetPutAndConflict() = runBlocking {
        val server = MockWebServer()
        var saved: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/conversations" -> MockResponse().setBody("""[{"id":"1795702727607","folder":"","name":"Ghost","pinned":false,"createdAt":1,"updatedAt":5,"tokenCount":10,"model":"m"}]""")
                request.path == "/conversations/1795702727607" && request.method == "GET" -> MockResponse().setBody(LMS_CHAT).setHeader("X-Updated-At", "777")
                request.path == "/conversations/new%20chat" && request.method == "PUT" -> {
                    saved = request.body.readUtf8()
                    MockResponse().setBody("""{"ok":true}""").setHeader("X-Updated-At", "888")
                }
                request.path == "/conversations/1795702727607" && request.method == "PUT" ->
                    if (request.getHeader("X-Base-Updated-At") == "1") MockResponse().setResponseCode(409).setBody("""{"error":"changed on the PC","updatedAt":999}""")
                    else MockResponse().setBody("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val b = BridgeClient(server.url("/").toString().trimEnd('/'))
        assertEquals("Ghost", b.list().single().title)
        val d = b.get("1795702727607")
        assertEquals(777, d.updatedAt)
        assertEquals(2, d.messages.size)
        assertEquals(888, b.put("new chat", "{}", null))
        assertEquals("{}", saved)
        try { b.put("1795702727607", "{}", 1); fail() } catch (c: ChatConflict) { assertEquals(999, c.pcUpdatedAt) }
        server.shutdown()
    }
}

@Suppress("unused")
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content
@Suppress("unused")
private fun JsonObject.a(k: String) = this[k] as? JsonArray
