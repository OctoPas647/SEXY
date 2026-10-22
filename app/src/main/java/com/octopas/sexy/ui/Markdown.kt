package com.octopas.sexy.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val indent: Int, val marker: String, val text: String) : MdBlock
    data class Code(val lang: String, val code: String, val isTable: Boolean = false) : MdBlock
    data object Rule : MdBlock
}

private val headingRe = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRe = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val ruleRe = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

/** Small Markdown parser good enough for chat answers (works on half-streamed text too). */
fun parseMarkdown(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = text.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim()); para.clear() }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n'); i++
                }
                out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            }
            trimmed.startsWith("|") -> {
                flush()
                val table = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { table.append(lines[i].trim()).append('\n'); i++ }
                i--
                out += MdBlock.Code("", table.toString().trimEnd('\n'), isTable = true)
            }
            line.isBlank() -> flush()
            ruleRe.matches(line) -> { flush(); out += MdBlock.Rule }
            headingRe.matches(trimmed) -> {
                flush()
                val m = headingRe.find(trimmed)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            bulletRe.matches(line) -> {
                flush()
                val m = bulletRe.find(line)!!
                val marker = m.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                out += MdBlock.Bullet(m.groupValues[1].length / 2, marker, m.groupValues[3])
            }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

private val inlineRe = Regex("`[^`\\n]+`|\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|\\*[^*\\s][^*\\n]*\\*")

fun inlineMarkdown(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    inlineRe.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        val t = m.value
        when {
            t.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 0.92.em)) {
                append(t.substring(1, t.length - 1))
            }
            t.startsWith("**") || t.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.substring(2, t.length - 2)) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Para -> Text(inlineMarkdown(b.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                is MdBlock.Heading -> Text(
                    inlineMarkdown(b.text, codeBg),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                    Text(b.marker, Modifier.width(24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text(inlineMarkdown(b.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Code -> CodeBlock(b.lang, b.code, showHeader = !b.isTable)
                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String, showHeader: Boolean = true) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (showHeader) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        lang.ifBlank { "code" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                        Icon(Icons.Outlined.ContentCopy, "Copy code", Modifier.size(18.dp))
                    }
                }
            }
            Text(
                code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = if (showHeader) 0.dp else 12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                softWrap = false,
            )
        }
    }
}
