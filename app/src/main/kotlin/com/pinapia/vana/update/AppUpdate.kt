package com.pinapia.vana.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class AppRelease(
    val tag: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val apk: ApkAsset?,
) {
    data class ApkAsset(
        val name: String,
        val url: String,
        val sizeBytes: Long,
    )
}

sealed class AppUpdateStatus {
    data class UpToDate(val latestVersion: String) : AppUpdateStatus()
    data class Available(val release: AppRelease) : AppUpdateStatus()
}

object AppUpdate {
    const val OWNER = "zjywill"
    const val REPO = "Vana-Android"
    const val LATEST_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    fun versionNameFromTag(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V")

    fun compareVersions(left: String, right: String): Int {
        val a = versionParts(left)
        val b = versionParts(right)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val da = a.getOrElse(i) { 0 }
            val db = b.getOrElse(i) { 0 }
            if (da != db) return da.compareTo(db)
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean =
        compareVersions(candidate, current) > 0

    fun evaluate(currentVersion: String, release: AppRelease): AppUpdateStatus {
        return if (isNewer(release.versionName, currentVersion)) {
            AppUpdateStatus.Available(release)
        } else {
            AppUpdateStatus.UpToDate(release.versionName)
        }
    }

    fun parseLatest(body: String): AppRelease = toRelease(json.decodeFromString(GitHubReleaseDto.serializer(), body))

    fun pickApk(assets: List<AppRelease.ApkAsset>, versionName: String): AppRelease.ApkAsset? {
        val expected = "Vana-$versionName.apk"
        return assets.firstOrNull { it.name.equals(expected, ignoreCase = true) }
            ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }

    internal fun toRelease(dto: GitHubReleaseDto): AppRelease {
        val versionName = versionNameFromTag(dto.tagName)
        val assets = dto.assets.map {
            AppRelease.ApkAsset(name = it.name, url = it.browserDownloadUrl, sizeBytes = it.size)
        }
        return AppRelease(
            tag = dto.tagName,
            versionName = versionName,
            title = dto.name?.takeIf { it.isNotBlank() } ?: "Vana $versionName",
            notes = dto.body.orEmpty().trim(),
            htmlUrl = dto.htmlUrl,
            apk = pickApk(assets, versionName),
        )
    }

    private fun versionParts(raw: String): List<Int> =
        versionNameFromTag(raw)
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    internal data class GitHubReleaseDto(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        val body: String? = null,
        val assets: List<GitHubAssetDto> = emptyList(),
    )

    @Serializable
    internal data class GitHubAssetDto(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0,
    )
}
