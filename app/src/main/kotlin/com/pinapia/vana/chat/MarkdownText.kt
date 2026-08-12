package com.pinapia.vana.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Numbered(val index: Int, val text: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
}

/**
 * 轻量 Markdown：粗体、列表、标题、表格。不做 WebView。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseBlocks(rebalanceCjkEmphasis(markdown)) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = inlineMarkdown(block.text),
                        style = style,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = inlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                is MdBlock.Bullet -> {
                    Text(
                        text = buildAnnotatedString {
                            append("• ")
                            append(inlineMarkdown(block.text))
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
                is MdBlock.Numbered -> {
                    Text(
                        text = buildAnnotatedString {
                            append("${block.index}. ")
                            append(inlineMarkdown(block.text))
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
                is MdBlock.Table -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            block.headers.joinToString(" | "),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        block.rows.forEach { row ->
                            Text(
                                row.joinToString(" | "),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun rebalanceCjkEmphasis(text: String): String =
    text.replace(Regex("""\*\*([^*]+?)：\*\*"""), "**$1**：")
        .replace(Regex("""\*\*([^*]+?):\*\*"""), "**$1**:")

private fun parseBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MdBlock.Paragraph(text)
        paragraph.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val heading = Regex("""^(#{1,6})\s+(.*)$""").matchEntire(line)
        val bullet = Regex("""^[-*+]\s+(.*)$""").matchEntire(line)
        val numbered = Regex("""^(\d+)\.\s+(.*)$""").matchEntire(line)
        val tableRow = line.trim().startsWith("|") && line.trim().endsWith("|")

        when {
            heading != null -> {
                flushParagraph()
                blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            }
            tableRow && i + 1 < lines.size && lines[i + 1].contains(Regex("""\|?\s*:?-{3,}""")) -> {
                flushParagraph()
                val headers = splitTableRow(line)
                i += 1 // delimiter
                val rows = mutableListOf<List<String>>()
                while (i + 1 < lines.size) {
                    val next = lines[i + 1]
                    if (!(next.trim().startsWith("|") && next.trim().endsWith("|"))) break
                    i += 1
                    rows += splitTableRow(next)
                }
                blocks += MdBlock.Table(headers, rows)
            }
            bullet != null -> {
                flushParagraph()
                blocks += MdBlock.Bullet(bullet.groupValues[1].trim())
            }
            numbered != null -> {
                flushParagraph()
                blocks += MdBlock.Numbered(
                    numbered.groupValues[1].toIntOrNull() ?: 1,
                    numbered.groupValues[2].trim(),
                )
            }
            line.isBlank() -> flushParagraph()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i += 1
    }
    flushParagraph()
    return blocks.ifEmpty { listOf(MdBlock.Paragraph(source)) }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var remaining = text
    val patterns = listOf(
        Regex("""\*\*(.+?)\*\*""") to SpanStyle(fontWeight = FontWeight.Bold),
        Regex("""__(.+?)__""") to SpanStyle(fontWeight = FontWeight.Bold),
        Regex("""\*(.+?)\*""") to SpanStyle(fontStyle = FontStyle.Italic),
        Regex("""`(.+?)`""") to SpanStyle(fontFamily = FontFamily.Monospace),
    )
    while (remaining.isNotEmpty()) {
        var earliest: MatchResult? = null
        var style: SpanStyle? = null
        for ((regex, span) in patterns) {
            val match = regex.find(remaining) ?: continue
            if (earliest == null || match.range.first < earliest.range.first) {
                earliest = match
                style = span
            }
        }
        if (earliest == null || style == null) {
            append(remaining)
            break
        }
        append(remaining.substring(0, earliest.range.first))
        withStyle(style) { append(earliest.groupValues[1]) }
        remaining = remaining.substring(earliest.range.last + 1)
    }
}
