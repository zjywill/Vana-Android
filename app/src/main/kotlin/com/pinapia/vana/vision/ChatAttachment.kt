package com.pinapia.vana.vision

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    var text: String = "",
    var droppedLines: Int = 0,
    var imageFileName: String? = null,
    var documentName: String? = null,
    var sendsImage: Boolean = false,
    val createdAt: Instant = Clock.System.now(),
    @Transient var imagePayload: String? = null,
) {
    val isDocument: Boolean get() = documentName != null
    val hasText: Boolean get() = text.isNotBlank()
    val carriesImage: Boolean get() = sendsImage && imagePayload != null

    companion object {
        const val MAX_CHARACTERS = 4000
        const val MAX_ATTACHMENTS = 6

        fun modelText(typed: String, attachments: List<ChatAttachment>): String {
            if (attachments.isEmpty()) return typed
            val blocks = mutableListOf<String>()
            val typedText = typed.trim()
            if (typedText.isNotEmpty()) blocks += typedText
            blocks += preamble(attachments)
            var imageNumber = 0
            attachments.forEachIndexed { index, attachment ->
                var attached: Int? = null
                if (attachment.carriesImage) {
                    imageNumber += 1
                    attached = imageNumber
                }
                blocks += attachment.block(number = index + 1, attachedImage = attached)
            }
            return blocks.joinToString("\n\n")
        }

        private fun preamble(attachments: List<ChatAttachment>): String {
            val photos = attachments.count { !it.isDocument }
            val documents = attachments.size - photos
            val images = attachments.count { it.carriesImage }
            val attached = if (images > 0) {
                "其中 $images 张本机一个字都没认出来，原图直接附在这条消息里了，请看图。"
            } else {
                ""
            }
            return when {
                documents == 0 ->
                    "（以下是用户拍的 $photos 张照片在本机识别出的文字，不是他打的字。$attached）"
                photos == 0 ->
                    "（以下是用户选的 $documents 份文件里的文字，原样取出，不是他打的字。）"
                else ->
                    "（以下是用户随这句话带来的 ${attachments.size} 件东西里的文字，都不是他打的字：" +
                        "照片是本机识别的，可能有错；文件是原样取出的。$attached）"
            }
        }
    }

    private fun block(number: Int, attachedImage: Int?): String {
        var header = if (isDocument) "【文件 $number：${documentName.orEmpty()}】" else "【照片 $number】"
        if (droppedLines > 0) {
            val tail = "太长，后面 $droppedLines 行没有取进来】"
            header = if (isDocument) {
                "【文件 $number：${documentName.orEmpty()}·$tail"
            } else {
                "【照片 $number·$tail"
            }
        }
        return "$header\n${body(attachedImage)}"
    }

    private fun body(attachedImage: Int?): String {
        val lines = mutableListOf<String>()
        when {
            hasText -> lines += text
            isDocument -> lines += "（这份文件里没有取到文字。）"
            attachedImage == null ->
                lines += "（没有识别到文字。Vana 现在只能读照片里的字，看不了图像本身。）"
            else -> lines += "（本机没有识别到文字。）"
        }
        if (attachedImage != null) {
            lines += "（用户同意把原图发给你：它是这条消息随附的第 $attachedImage 张图，直接看图回答。）"
        }
        return lines.joinToString("\n")
    }
}

data class DraftAttachment(
    val id: String = UUID.randomUUID().toString(),
    var preview: android.graphics.Bitmap? = null,
    var text: String = "",
    var droppedLines: Int = 0,
    var isRecognizing: Boolean = false,
    var isLoading: Boolean = true,
    var failure: String? = null,
    var sendsImage: Boolean = false,
    var imageBytes: ByteArray? = null,
    var documentName: String? = null,
) {
    val hasText: Boolean get() = text.isNotBlank()
    val isDocument: Boolean get() = documentName != null
    val canSendImage: Boolean get() = imageBytes != null && failure == null && !isLoading && !isRecognizing

    fun suggestsImage(under: PhotoImagePolicy): Boolean =
        canSendImage && under.offers(hasText)

    fun toChatAttachment(
        persist: Boolean,
        store: AttachmentStore?,
    ): ChatAttachment {
        val bytes = imageBytes
        val payload = if (sendsImage && bytes != null) bytes.toBase64() else null
        // 隐私会话不落盘——只进内存缓存，进程死了就没了。
        val fileName = when {
            persist && bytes != null && store != null -> store.store(bytes, id)
            !persist && bytes != null && store != null -> {
                store.cache(bytes, id)
                null
            }
            else -> null
        }
        return ChatAttachment(
            id = id,
            text = text,
            droppedLines = droppedLines,
            imageFileName = fileName,
            documentName = documentName,
            sendsImage = sendsImage,
            imagePayload = payload,
        )
    }
}
