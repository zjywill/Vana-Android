package com.pinapia.vana.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class AppUpdateInstaller(
    private val context: Context,
    private val httpClient: OkHttpClient = downloadClient,
) {
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installPermissionSettingsIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun download(
        apk: AppRelease.ApkAsset,
        onProgress: (Float?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, apk.name.ifBlank { "vana-update.apk" })
        val request = Request.Builder()
            .url(apk.url)
            .get()
            .header("User-Agent", "Vana (+https://github.com/${AppUpdate.OWNER}/${AppUpdate.REPO})")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppUpdateError("下载失败（${response.code}）。")
            }
            val body = response.body ?: throw AppUpdateError("下载失败，文件是空的。")
            val total = body.contentLength()
            var read = 0L
            var lastPercent = -1
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        val percent = if (total > 0) ((read * 100) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main.immediate) {
                                onProgress(if (total > 0) (percent / 100f).coerceIn(0f, 1f) else null)
                            }
                        }
                    }
                }
            }
        }
        if (!target.exists() || target.length() <= 0L) {
            throw AppUpdateError("下载失败，文件是空的。")
        }
        target
    }

    companion object {
        const val DIR = "updates"

        internal val downloadClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
