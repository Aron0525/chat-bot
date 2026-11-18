package com.aron.chatbot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub Releases/latest 的 JSON 解析。接口字段一旦变形，用户就会看到
 * 「解析失败」或者更糟：把一个不是安装包的地址当 apk 下载。
 */
class ReleaseParserTest {

    private val fullRelease = """
        {
          "tag_name": "v1.8",
          "name": "Jev 1.8",
          "body": "**新增** 在线更新",
          "html_url": "https://github.com/Aron0525/chat-bot/releases/tag/v1.8",
          "assets": [
            {
              "name": "jev-1.8-source.zip",
              "size": 12345,
              "browser_download_url": "https://example.com/jev-1.8-source.zip"
            },
            {
              "name": "jev-1.8.apk",
              "size": 6543210,
              "browser_download_url": "https://example.com/jev-1.8.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesTagNotesAndApkAsset() {
        val r = requireNotNull(UpdateChecker.parseRelease(fullRelease))
        assertEquals("v1.8", r.tag)
        assertEquals("1.8", r.versionName)
        assertTrue(r.hasApk)
        assertEquals("https://example.com/jev-1.8.apk", r.apkUrl)
        assertEquals("jev-1.8.apk", r.apkName)
        assertEquals(6543210L, r.apkSize)
        assertEquals("https://github.com/Aron0525/chat-bot/releases/tag/v1.8", r.pageUrl)
        assertEquals("**新增** 在线更新", r.notes)
    }

    @Test
    fun picksTheApkAmongOtherAssets() {
        val r = requireNotNull(UpdateChecker.parseRelease(fullRelease))
        // 第一个附件是 zip，不能被当成安装包
        assertFalse(r.apkUrl.endsWith(".zip", ignoreCase = true))
    }

    @Test
    fun releaseWithoutApkStillParsesButHasNoDownload() {
        val json = """
            {
              "tag_name": "v1.9",
              "html_url": "https://example.com/r/v1.9",
              "assets": [{"name": "notes.txt", "size": 10, "browser_download_url": "https://example.com/notes.txt"}]
            }
        """.trimIndent()
        val r = requireNotNull(UpdateChecker.parseRelease(json))
        assertFalse(r.hasApk)
        assertEquals("", r.apkUrl)
        assertEquals("1.9", r.versionName)
    }

    @Test
    fun missingBodyAndAssetsDoNotCrash() {
        val json = """{"tag_name":"v2.0"}"""
        val r = requireNotNull(UpdateChecker.parseRelease(json))
        assertEquals("2.0", r.versionName)
        assertEquals("", r.notes)
        assertFalse(r.hasApk)
    }

    @Test
    fun garbageOrTaglessJsonIsNull() {
        assertNull(UpdateChecker.parseRelease("not json at all"))
        assertNull(UpdateChecker.parseRelease("{}"))
        assertNull(UpdateChecker.parseRelease("""{"tag_name":""}"""))
    }
}