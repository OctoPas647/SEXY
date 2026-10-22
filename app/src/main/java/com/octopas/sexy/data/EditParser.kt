package com.octopas.sexy.data

/** A file the AI wants to create/replace (newContent) or delete (newContent == null). */
data class FileEdit(val path: String, val newContent: String?)

data class ParsedEdits(val summary: String, val edits: List<FileEdit>)

/**
 * The AI is told to answer with blocks like:
 *
 *   <<<FILE app/src/main/java/Foo.kt>>>
 *   ...full new file...
 *   <<<END>>>
 *   <<<DELETE path/to/Old.kt>>>
 */
object EditParser {
    private val fileBlock = Regex("<<<FILE\\s+([^>\\r\\n]+?)\\s*>>>[ \\t]*\\r?\\n(.*?)<<<END>>>", RegexOption.DOT_MATCHES_ALL)
    private val deleteBlock = Regex("<<<DELETE\\s+([^>\\r\\n]+?)\\s*>>>")
    private val openFence = Regex("^```[\\w.+-]*[ \\t]*\\r?\\n")
    private val closeFence = Regex("\\r?\\n?```[ \\t]*\\r?\\n?$")

    fun parse(text: String): ParsedEdits {
        val found = LinkedHashMap<String, FileEdit>()
        data class Hit(val start: Int, val edit: FileEdit)
        val hits = mutableListOf<Hit>()
        fileBlock.findAll(text).forEach { m ->
            val path = cleanPath(m.groupValues[1])
            if (path.isNotEmpty()) hits += Hit(m.range.first, FileEdit(path, cleanContent(m.groupValues[2])))
        }
        deleteBlock.findAll(text).forEach { m ->
            val path = cleanPath(m.groupValues[1])
            if (path.isNotEmpty()) hits += Hit(m.range.first, FileEdit(path, null))
        }
        hits.sortedBy { it.start }.forEach { found[it.edit.path] = it.edit } // last one for a path wins
        val firstTag = text.indexOf("<<<").let { if (it < 0) text.length else it }
        val summary = text.substring(0, firstTag).trim()
        return ParsedEdits(summary, found.values.toList())
    }

    fun cleanPath(raw: String): String = raw.trim().trim('`', '"', '\'').removePrefix("./").trimStart('/')

    private fun cleanContent(raw: String): String {
        var c = raw.trimEnd('\r', '\n', ' ', '\t')
        // Some models wrap the file in ``` fences anyway - strip them only when both are present.
        val open = openFence.find(c)
        if (open != null && open.range.first == 0 && closeFence.containsMatchIn(c)) {
            c = c.substring(open.range.last + 1).replace(closeFence, "")
        }
        return if (c.isEmpty()) "" else c.trimEnd('\r', '\n') + "\n"
    }

    /** Formats repo files for the prompt in the same block format the AI must answer in. */
    fun formatFiles(files: Map<String, String>): String = buildString {
        files.forEach { (path, content) ->
            append("<<<FILE ").append(path).append(">>>\n")
            append(content.trimEnd('\n')).append("\n<<<END>>>\n\n")
        }
    }
}
