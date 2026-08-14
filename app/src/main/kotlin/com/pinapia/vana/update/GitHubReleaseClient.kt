package com.pinapia.vana.update

import com.pinapia.vana.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AppUpdateError(message: String) : Exception(message)

fun interface GitHubReleaseClient {
    suspend fun latest(): AppRelease

    companion object {
        fun github(httpClient: OkHttpClient = checkClient): GitHubReleaseClient =
            GitHubReleaseClient {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(AppUpdate.LATEST_API)
                        .get()
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "Vana/${BuildConfig.VERSION_NAME} (+https://github.com/${AppUpdate.OWNER}/${AppUpdate.REPO})")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw AppUpdateError(messageForStatus(response.code))
                        }
                        if (body.isBlank()) {
                            throw AppUpdateError("GitHub 没有返回版本信息。")
                        }
                        AppUpdate.parseLatest(body)
                    }
                }
            }

        fun messageForStatus(code: Int): String = when (code) {
            403, 429 -> "GitHub 暂时限流了，过一会儿再试。"
            404 -> "还没有发布过正式版。"
            else -> "连不上 GitHub（$code），过一会儿再试。"
        }

        internal val checkClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
