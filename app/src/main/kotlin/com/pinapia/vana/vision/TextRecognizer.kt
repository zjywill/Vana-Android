package com.pinapia.vana.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class RecognizedText(
    val text: String,
    val droppedLines: Int = 0,
) {
    val isEmpty: Boolean get() = text.isBlank()
}

object TextRecognizer {
    private const val MINIMUM_TEXT_HEIGHT_FRACTION = 0.008f

    suspend fun recognize(bitmap: Bitmap): RecognizedText = withContext(Dispatchers.Default) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = suspendCancellableCoroutine { cont ->
                client.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val imageWidth = bitmap.width.coerceAtLeast(1).toFloat()
            val imageHeight = bitmap.height.coerceAtLeast(1).toFloat()
            val minHeight = (bitmap.height * MINIMUM_TEXT_HEIGHT_FRACTION).coerceAtLeast(1f)
            val fragments = result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    if (box.height() < minHeight) return@mapNotNull null
                    val text = line.text.trim()
                    if (text.isEmpty()) return@mapNotNull null
                    RecognizedFragment(
                        text = text,
                        x = box.left / imageWidth,
                        y = box.top / imageHeight,
                        width = box.width() / imageWidth,
                        height = box.height() / imageHeight,
                    )
                }
            val joined = RecognizedTextLayout.reconstruct(fragments)
            val (clipped, dropped) = RecognizedTextLayout.truncated(joined, ChatAttachment.MAX_CHARACTERS)
            RecognizedText(text = clipped, droppedLines = dropped)
        } finally {
            client.close()
        }
    }

    fun truncate(text: String, maxCharacters: Int): Pair<String, Int> =
        RecognizedTextLayout.truncated(text, maxCharacters)
}

object AttachmentImage {
    const val MAX_PIXEL_SIZE = 1600
    const val COMPRESSION_QUALITY = 70

    fun jpegData(bitmap: Bitmap): ByteArray {
        val scaled = scaleDown(bitmap, MAX_PIXEL_SIZE)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, stream)
        if (scaled !== bitmap) scaled.recycle()
        return stream.toByteArray()
    }

    fun decode(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
