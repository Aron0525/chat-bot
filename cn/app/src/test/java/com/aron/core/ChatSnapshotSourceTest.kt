package com.aron.core

import com.aron.chatbot.core.ChatSnapshot
import com.aron.chatbot.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 提示词按 App 分流的地基：ChatSnapshot.withSource 打的来源标记。
 * 推特（只看推特 / 一键直填）走 CommentPrompts、「其他」App 走聊天版，
 * 全靠 sourcePkg == "com.twitter.android" 这个判断——标记丢了，
 * 推特就会一直吃聊天版（只看最后一句那套）。锁住打标行为防回归。
 */
class ChatSnapshotSourceTest {

    @Test
    fun withSourceTagsTheSnapshot() {
        val snap = ChatSnapshot("标题", listOf(Msg("other", "你好")))
        assertNull(snap.sourcePkg)
        val tagged = snap.withSource("com.twitter.android")
        assertEquals("com.twitter.android", tagged.sourcePkg)
    }

    @Test
    fun withSourceKeepsEverythingElseIntact() {
        val snap = ChatSnapshot("标题", listOf(Msg("other", "一"), Msg("me", "二")))
        val tagged = snap.withSource("com.tencent.mobileqq")
        assertEquals(snap.title, tagged.title)
        assertEquals(snap.messages, tagged.messages)
    }

    @Test
    fun withSourceIsIdempotent() {
        val tagged = ChatSnapshot(null, emptyList()).withSource("com.twitter.android")
        assertEquals("com.twitter.android", tagged.withSource("com.twitter.android").sourcePkg)
    }
}
