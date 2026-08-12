package com.pinapia.vana.vision

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * 从 .docx 里取正文。
 *
 * **不走识别那条路。** docx 里的字就是字。docx 是个 zip，正文在 `word/document.xml`。
 * 表格用和识别结果同一个列分隔（[COLUMN_SEPARATOR]）。
 */
object DocxText {
    const val DOCUMENT_PATH = "word/document.xml"
    const val COLUMN_SEPARATOR = RecognizedTextLayout.COLUMN_SEPARATOR

    fun text(of: ByteArray): String {
        val xml = zipEntry(of, DOCUMENT_PATH)
            ?: throw IllegalArgumentException("这份文档里没有找到正文。")
        return textOfDocumentXml(xml)
    }

    fun textOfDocumentXml(xml: ByteArray): String {
        val reader = Reader()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> reader.start(localName(parser.name))
                XmlPullParser.END_TAG -> reader.end(localName(parser.name))
                XmlPullParser.TEXT -> reader.text(parser.text.orEmpty())
            }
            event = parser.next()
        }
        return collapseBlankLines(reader.finish())
    }

    private fun zipEntry(data: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) {
                    return zip.readBytes()
                }
            }
        }
        return null
    }

    private fun collapseBlankLines(text: String): String {
        val lines = mutableListOf<String>()
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() && lines.lastOrNull()?.isEmpty() == true) continue
            lines += trimmed
        }
        while (lines.firstOrNull()?.isEmpty() == true) lines.removeAt(0)
        while (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
    }

    private fun localName(elementName: String): String {
        val colon = elementName.lastIndexOf(':')
        return if (colon >= 0) elementName.substring(colon + 1) else elementName
    }

    private class Reader {
        private val lines = mutableListOf<String>()
        private var row = mutableListOf<String>()
        private var cell = ""
        private var paragraph = StringBuilder()
        private var cellDepth = 0
        private var insideTextRun = false

        fun finish(): String {
            flushParagraph()
            return lines.joinToString("\n")
        }

        fun start(name: String) {
            when (name) {
                "t" -> insideTextRun = true
                "tr" -> row = mutableListOf()
                "tc" -> {
                    cellDepth += 1
                    cell = ""
                }
                "tab" -> paragraph.append(' ')
                "br" -> paragraph.append(if (cellDepth > 0) ' ' else '\n')
            }
        }

        fun end(name: String) {
            when (name) {
                "t" -> insideTextRun = false
                "p" -> flushParagraph()
                "tc" -> {
                    flushParagraph()
                    row += cell.trim()
                    cell = ""
                    cellDepth = (cellDepth - 1).coerceAtLeast(0)
                }
                "tr" -> {
                    lines += row.joinToString(COLUMN_SEPARATOR)
                    row = mutableListOf()
                }
            }
        }

        fun text(string: String) {
            if (insideTextRun) paragraph.append(string)
        }

        private fun flushParagraph() {
            val text = paragraph.toString().trim()
            paragraph = StringBuilder()
            if (cellDepth <= 0) {
                lines += text
                return
            }
            if (text.isEmpty()) return
            cell = if (cell.isEmpty()) text else "$cell $text"
        }
    }
}
