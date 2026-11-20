package com.aron.chatbot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * api.github.com 被限流时的网页兜底通道：从 /releases/latest 的跳转地址或
 * 页面 HTML 里抠 tag，再按发版命名约定推出 apk 下载地址。
 * 命名约定（chat-bot-<tag>.apk）一旦和发版脚本不一致，用户会下载到 404。
 */
class UpdateFallbackTest {

    @Test
    fun tagFromRedirectUrl() {
        assertEquals(
            "v1.3",
            UpdateChecker.tagFromUrl("https://github.com/Aron0525/chat-bot/releases/tag/v1.3")
        )
    }

    @Test
    fun tagFromUrlIgnoresQueryAndFragment() {
        assertEquals(
            "v1.10",
            UpdateChecker.tagFromUrl(
                "https://github.com/Aron0525/chat-bot/releases/tag/v1.10?foo=1#top"
            )
        )
    }

    @Test
    fun tagFromUrlWithoutTagReturnsNull() {
        assertNull(UpdateChecker.tagFromUrl("https://github.com/Aron0525/chat-bot"))
    }

    @Test
    fun tagFromHtmlPicksFirstTag() {
        val html = """
            <a href="/Aron0525/chat-bot/releases/tag/v2.0">v2.0</a>
            <a href="/Aron0525/chat-bot/releases/tag/v1.9">older</a>
        """.trimIndent()
        assertEquals("v2.0", UpdateChecker.tagFromHtml(html))
    }

    @Test
    fun tagFromHtmlEmptyReturnsNull() {
        assertNull(UpdateChecker.tagFromHtml("<html>没有发版</html>"))
    }

    @Test
    fun releaseFromTagBuildsConventionUrls() {
        val r = UpdateChecker.releaseFromTag("v1.3")
        assertEquals("v1.3", r.tag)
        assertEquals("1.3", r.versionName)
        assertEquals(
            "https://github.com/Aron0525/chat-bot/releases/download/v1.3/chat-bot-v1.3.apk",
            r.apkUrl
        )
        assertEquals("chat-bot-v1.3.apk", r.apkName)
        assertEquals("https://github.com/Aron0525/chat-bot/releases/tag/v1.3", r.pageUrl)
        assertTrue(r.hasApk)
    }

    @Test
    fun releaseFromTagFeedsVersionCompare() {
        val r = UpdateChecker.releaseFromTag("v1.3")
        assertTrue(UpdateVersion.isNewer(r.versionName, "1.2"))
    }
}
