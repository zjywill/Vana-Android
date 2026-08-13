package com.pinapia.vana.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * 系统相机拍下来的那一张。
 *
 * Android 没有 VisionKit 那种带纠偏的文档扫描器（见 CLAUDE.md），所以「拍文件」在这边
 * 就是系统相机。相册那条路也走 [decode]：不少机子把旋转写在 EXIF 里，不转的话 OCR
 * 会把表格的行认成列。
 */
object CapturePhoto {
    const val AUTHORITY_SUFFIX = ".fileprovider"

    fun isAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)

    fun createUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg").apply { createNewFile() }
        return FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            file,
        )
    }

    fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        rotate(bitmap, orientation)
    }.getOrNull()

    fun cleanup(context: Context, uri: Uri) {
        uri.lastPathSegment?.let { name ->
            File(File(context.cacheDir, "camera"), name).delete()
        }
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
