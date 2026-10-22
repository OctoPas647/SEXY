package com.octopas.sexy

import com.octopas.sexy.data.DiffType
import com.octopas.sexy.data.EditParser
import com.octopas.sexy.data.LineDiff
import com.octopas.sexy.data.ServerUrl
import com.octopas.sexy.data.SseParser
import com.octopas.sexy.data.StreamEvent
import com.octopas.sexy.data.AiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test fun addsSchemeAndDefaultPort() {
        assertEquals("http://100.101.102.103:3030", ServerUrl.normalize("100.101.102.103"))
        assertEquals("http://desktop-d0efhda:3030", ServerUrl.normalize(" desktop-d0efhda "))
    }
    @Test fun keepsTypedPort() {
        assertEquals("http://desktop-d0efhda:8080", ServerUrl.normalize("desktop-d0efhda:8080"))
        assertEquals("http://100.1.2.3:3030", ServerUrl.normalize("100.1.2.3:3030/"))
    }
    @Test fun stripsV1() {
        assertEquals("http://pc:3030", ServerUrl.normalize("http://pc:3030/v1/"))
        assertEquals("http://pc:3030", ServerUrl.normalize("pc/v1"))
    }
    @Test fun schemeWithoutPortKeepsDefaultSchemePort() {
        assertEquals("https://pc.tail1234.ts.net", ServerUrl.normalize("https://pc.tail1234.ts.net"))
    }
    @Test fun bareNameTriesAllWaysTheServerCanBeShared() {
        assertEquals(
            listOf("http://desktop-d0efhda:3030", "https://desktop-d0efhda", "http://desktop-d0efhda"),
            ServerUrl.candidates("desktop-d0efhda"),
        )
    }
    @Test fun typedPortOrSchemeIsTakenLiterally() {
        assertEquals(listOf("http://desktop-d0efhda:8080"), ServerUrl.candidates("desktop-d0efhda:8080"))
        assertEquals(listOf("https://pc.tail1234.ts.net"), ServerUrl.candidates("https://pc.tail1234.ts.net"))
        assertTrue(ServerUrl.candidates("").isEmpty())
    }
    @Test fun rejectsGarbage() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("http://"))
    }
}

class SseParserTest {
    @Test fun contentAndReasoning() {
        val r = SseParser.parse("""data: {"choices":[{"index":0,"delta":{"reasoning_content":"hmm","content":null}}]}""")
        assertEquals(SseParser.Result.Events(listOf(StreamEvent.Reasoning("hmm"))), r)
        val c = SseParser.parse("""data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}""")
        assertEquals(SseParser.Result.Events(listOf(StreamEvent.Content("Hi"))), c)
    }
    @Test fun doneAndSkip() {
        assertEquals(SseParser.Result.Done, SseParser.parse("data: [DONE]"))
        assertEquals(SseParser.Result.Skip, SseParser.parse(""))
        assertEquals(SseParser.Result.Skip, SseParser.parse(": keep-alive"))
        assertEquals(SseParser.Result.Skip, SseParser.parse("""data: {"choices":[{"delta":{}}]}"""))
    }
    @Test fun stats() {
        val r = SseParser.parse(
            """data: {"choices":[{"finish_reason":"stop","delta":{}}],"usage":{"prompt_tokens":10,"completion_tokens":42},"timings":{"predicted_per_second":55.5}}"""
        ) as SseParser.Result.Events
        assertEquals(StreamEvent.Stats(55.5, 10, 42), r.events.single())
    }
    @Test(expected = AiException::class) fun errorThrows() {
        SseParser.parse("""data: {"error":{"code":500,"message":"context too long"}}""")
    }
}

class EditParserTest {
    @Test fun parsesFilesDeletesAndSummary() {
        val text = """
- Added a toggle
- Removed old helper

<<<FILE app/src/main/java/A.kt>>>
package a

fun a() = 1
<<<END>>>

<<<DELETE ./app/src/main/java/Old.kt>>>
<<<FILE /README.md >>>
```markdown
# Title
```
<<<END>>>
""".trimIndent()
        val p = EditParser.parse(text)
        assertEquals("- Added a toggle\n- Removed old helper", p.summary)
        assertEquals(3, p.edits.size)
        assertEquals("app/src/main/java/A.kt", p.edits[0].path)
        assertEquals("package a\n\nfun a() = 1\n", p.edits[0].newContent)
        assertEquals("app/src/main/java/Old.kt", p.edits[1].path)
        assertNull(p.edits[1].newContent)
        assertEquals("README.md", p.edits[2].path)
        assertEquals("# Title\n", p.edits[2].newContent) // fences stripped
    }
    @Test fun lastVersionOfAFileWins() {
        val p = EditParser.parse("<<<FILE a.txt>>>\none\n<<<END>>>\n<<<FILE a.txt>>>\ntwo\n<<<END>>>")
        assertEquals(1, p.edits.size)
        assertEquals("two\n", p.edits[0].newContent)
    }
    @Test fun noEdits() {
        val p = EditParser.parse("I need to see MainActivity.kt first.")
        assertTrue(p.edits.isEmpty())
        assertEquals("I need to see MainActivity.kt first.", p.summary)
    }
    @Test fun roundTripFormat() {
        val f = EditParser.formatFiles(mapOf("x/y.kt" to "val a = 1\n"))
        val p = EditParser.parse(f)
        assertEquals("val a = 1\n", p.edits.single().newContent)
    }
}

class LineDiffTest {
    @Test fun simpleChange() {
        val d = LineDiff.diff("a\nb\nc\n", "a\nB\nc\nd\n")
        val (add, del) = LineDiff.counts(d)
        assertEquals(2, add)
        assertEquals(1, del)
        assertEquals(listOf(DiffType.SAME, DiffType.DEL, DiffType.ADD, DiffType.SAME, DiffType.ADD), d.map { it.type })
    }
    @Test fun newFile() {
        val d = LineDiff.diff("", "x\ny\n")
        assertEquals(2, d.size)
        assertTrue(d.all { it.type == DiffType.ADD })
    }
    @Test fun compactCollapsesUnchanged() {
        val old = (1..50).joinToString("\n") + "\n"
        val new = old.replace("\n25\n", "\nTWENTY-FIVE\n")
        val c = LineDiff.compact(LineDiff.diff(old, new), context = 2)
        assertEquals(DiffType.GAP, c.first().type)
        assertEquals(DiffType.GAP, c.last().type)
        assertEquals(1, c.count { it.type == DiffType.ADD })
        assertEquals(1, c.count { it.type == DiffType.DEL })
    }
}
