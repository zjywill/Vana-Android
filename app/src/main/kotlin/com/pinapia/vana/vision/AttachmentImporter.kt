package com.pinapia.vana.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.pinapia.vana.ui.L10n
import java.io.File
import java.nio.charset.Charset

/**
 * 从「文件」里选进来的东西。
 *
 * 两条路，按「里面的信息是不是字」分：
 * - PDF / 图片：渲染成图走本机 OCR（多数体检报告是扫描件）。
 * - Word / txt：直接取文本（[DocxText] / [PlainTextFile]）。
 *
 * ML Kit Document Scanner 这里不接：要额外依赖和 Activity 合约，收益只是多一条拍文档入口，
 * 而相机 + 相册 + 文件三条已经够用。要接的话从 `GmsDocumentScanning` 起步。
 */
sealed class ImportedAttachment {
    data class Photo(val bitmap: Bitmap) : ImportedAttachment()
    data class Document(
        val name: String,
        val text: String,
        val droppedLines: Int,
        val failure: String?,
    ) : ImportedAttachment()
}

object AttachmentImporter {
    /** 一份 PDF 最多取前几页。体检报告动辄二十页，后面多是说明和广告。 */
    const val MAX_PAGES = 6

    val MIME_TYPES = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain",
        "text/markdown",
        "text/csv",
        "text/*",
        "image/*",
    )

    fun load(context: Context, uri: Uri): List<ImportedAttachment> {
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: L10n.text("文件", "File")
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()

        return when {
            mime == "application/pdf" || extension == "pdf" ->
                pages(context, uri).map { ImportedAttachment.Photo(it) }
            mime.contains("wordprocessingml") || extension == "docx" ->
                listOf(document(name) {
                    val bytes = readBytes(context, uri)
                        ?: error(L10n.text("这个文件读不出来。", "This file could not be read."))
                    DocxText.text(bytes)
                })
            mime.startsWith("text/") || extension in PLAIN_EXTENSIONS ->
                listOf(document(name) {
                    val bytes = readBytes(context, uri)
                        ?: error(L10n.text("这个文件读不出来。", "This file could not be read."))
                    PlainTextFile.decode(bytes)
                })
            mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> {
                val bytes = readBytes(context, uri)
                val bitmap = bytes?.let { AttachmentImage.decode(it) }
                if (bitmap != null) {
                    listOf(ImportedAttachment.Photo(bitmap))
                } else {
                    listOf(
                        ImportedAttachment.Document(
                            name = name,
                            text = "",
                            droppedLines = 0,
                            failure = L10n.text(
                                "这个格式读不了，试试 PDF、Word、纯文本或者照片。",
                                "This format is not supported. Try a PDF, Word document, plain-text file or photo.",
                            ),
                        ),
                    )
                }
            }
            else -> listOf(
                ImportedAttachment.Document(
                    name = name,
                    text = "",
                    droppedLines = 0,
                    failure = L10n.text(
                        "这个格式读不了，试试 PDF、Word、纯文本或者照片。",
                        "This format is not supported. Try a PDF, Word document, plain-text file or photo.",
                    ),
                ),
            )
        }
    }

    private fun document(name: String, reading: () -> String): ImportedAttachment.Document =
        try {
            val raw = reading()
            val (text, dropped) = TextRecognizer.truncate(raw, ChatAttachment.MAX_CHARACTERS)
            ImportedAttachment.Document(name, text, dropped, null)
        } catch (error: Exception) {
            ImportedAttachment.Document(
                name = name,
                text = "",
                droppedLines = 0,
                failure = error.message ?: L10n.text("这份文件读不出来。", "This file could not be read."),
            )
        }

    private fun pages(context: Context, uri: Uri): List<Bitmap> {
        val descriptor = openDescriptor(context, uri) ?: return emptyList()
        descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PAGES)
                return (0 until count).mapNotNull { index ->
                    renderer.openPage(index).use { page ->
                        // 按两倍渲染：A4 原尺寸每行字只有十来个像素高，识别率会塌。
                        val scale = 2f
                        val width = (page.width * scale).toInt()
                        val height = (page.height * scale).toInt()
                        if (width <= 0 || height <= 0) return@mapNotNull null
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            Canvas(bitmap).drawColor(Color.WHITE)
                            val transform = Matrix().apply { setScale(scale, scale) }
                            page.render(
                                bitmap,
                                null,
                                transform,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            // 部分 ContentProvider 不给 FD，落到临时文件再开。
            val bytes = readBytes(context, uri) ?: return null
            val temp = File.createTempFile("vana-pdf-", ".pdf", context.cacheDir)
            temp.writeBytes(bytes)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return null
    }

    private val PLAIN_EXTENSIONS = setOf("txt", "md", "csv", "tsv", "json", "log")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp")
}

/**
 * txt。中文 Windows 上导出来的十有八九不是 UTF-8。
 *
 * **「先试 UTF-8，不行再试 GB」是错的**：GBK 的两字节汉字常常正好是一段合法 UTF-8。
 * 所以要看解出来的像不像人话。
 */
object PlainTextFile {
    fun decode(data: ByteArray): String {
        bomEncoding(data)?.let { enc ->
            return String(data, enc)
        }
        val utf8 = runCatching { String(data, Charsets.UTF_8) }.getOrNull()
        if (utf8 != null && !looksMisread(utf8)) return utf8
        val chinese = runCatching { String(data, GB18030) }.getOrNull()
        if (chinese != null && containsChinese(chinese)) return chinese
        if (utf8 != null) return utf8
        error("这个文本文件的编码认不出来，另存成 UTF-8 再试。")
    }

    private fun looksMisread(text: String): Boolean {
        var run = 0
        for (scalar in text) {
            val code = scalar.code
            if (code in 0x80..0x7FF) {
                run += 1
                if (run >= 2) return true
            } else {
                run = 0
            }
        }
        return false
    }

    private fun containsChinese(text: String): Boolean =
        text.any { it.code in 0x4E00..0x9FFF }

    private fun bomEncoding(data: ByteArray): Charset? {
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        return null
    }

    private val GB18030: Charset = Charset.forName("GB18030")
}
