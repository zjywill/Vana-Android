package com.pinapia.vana.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun stripsVPrefix() {
        assertEquals("1.0.1", AppUpdate.versionNameFromTag("v1.0.1"))
        assertEquals("1.0.1", AppUpdate.versionNameFromTag("1.0.1"))
    }

    @Test
    fun comparesSemver() {
        assertTrue(AppUpdate.isNewer("1.0.2", "1.0.1"))
        assertFalse(AppUpdate.isNewer("1.0.1", "1.0.1"))
        assertFalse(AppUpdate.isNewer("1.0.0", "1.0.1"))
        assertTrue(AppUpdate.isNewer("v1.1.0", "1.0.9"))
        assertEquals(0, AppUpdate.compareVersions("1.0", "1.0.0"))
        assertTrue(AppUpdate.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun sameReleaseIsUpToDate() {
        val release = sample(tag = "v1.0.1")
        val status = AppUpdate.evaluate("1.0.1", release)
        assertTrue(status is AppUpdateStatus.UpToDate)
        assertEquals("1.0.1", (status as AppUpdateStatus.UpToDate).latestVersion)
    }

    @Test
    fun newerReleaseIsAvailable() {
        val release = sample(tag = "v1.0.2")
        val status = AppUpdate.evaluate("1.0.1", release)
        assertTrue(status is AppUpdateStatus.Available)
        assertEquals("1.0.2", (status as AppUpdateStatus.Available).release.versionName)
    }

    @Test
    fun parsesGithubLatestJson() {
        val release = AppUpdate.parseLatest(SAMPLE_JSON)
        assertEquals("v1.0.1", release.tag)
        assertEquals("1.0.1", release.versionName)
        assertEquals("Vana 1.0.1", release.title)
        assertEquals("Vana-1.0.1.apk", release.apk?.name)
        assertTrue(release.apk!!.url.endsWith("/Vana-1.0.1.apk"))
        assertTrue(release.notes.contains("覆盖安装"))
    }

    @Test
    fun prefersVersionedApkName() {
        val picked = AppUpdate.pickApk(
            listOf(
                AppRelease.ApkAsset("notes.txt", "https://example/notes.txt", 12),
                AppRelease.ApkAsset("Vana-1.0.2.apk", "https://example/Vana-1.0.2.apk", 10),
                AppRelease.ApkAsset("app-release.apk", "https://example/app-release.apk", 11),
            ),
            "1.0.2",
        )
        assertEquals("Vana-1.0.2.apk", picked?.name)
    }

    @Test
    fun missingApkStaysNull() {
        val release = AppUpdate.parseLatest(
            """
            {"tag_name":"v1.0.3","html_url":"https://github.com/zjywill/Vana-Android/releases/tag/v1.0.3","assets":[]}
            """.trimIndent(),
        )
        assertNull(release.apk)
        assertTrue(AppUpdate.evaluate("1.0.1", release) is AppUpdateStatus.Available)
    }

    @Test
    fun statusMessageForGithubErrors() {
        assertEquals("GitHub 暂时限流了，过一会儿再试。", GitHubReleaseClient.messageForStatus(403))
        assertEquals("还没有发布过正式版。", GitHubReleaseClient.messageForStatus(404))
    }

    private fun sample(tag: String) = AppRelease(
        tag = tag,
        versionName = AppUpdate.versionNameFromTag(tag),
        title = "Vana ${AppUpdate.versionNameFromTag(tag)}",
        notes = "修复",
        htmlUrl = "https://github.com/zjywill/Vana-Android/releases/tag/$tag",
        apk = AppRelease.ApkAsset(
            name = "Vana-${AppUpdate.versionNameFromTag(tag)}.apk",
            url = "https://example/${AppUpdate.versionNameFromTag(tag)}.apk",
            sizeBytes = 1,
        ),
    )

    companion object {
        private val SAMPLE_JSON = """
            {
              "tag_name": "v1.0.1",
              "name": "Vana 1.0.1",
              "html_url": "https://github.com/zjywill/Vana-Android/releases/tag/v1.0.1",
              "body": "- 工具 chip 按发生顺序跟在引出它的那句后面\n\n覆盖安装请用同一把签名。",
              "assets": [
                {
                  "name": "Vana-1.0.1.apk",
                  "browser_download_url": "https://github.com/zjywill/Vana-Android/releases/download/v1.0.1/Vana-1.0.1.apk",
                  "size": 49268560
                }
              ]
            }
        """.trimIndent()
    }
}
