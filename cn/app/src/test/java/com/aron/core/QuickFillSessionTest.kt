package com.aron.chatbot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「一键直填」的循环逻辑：同一轮对话里 1→2→3→1 循环，
 * 对方发新消息就必须作废重来（否则会拿旧回复去套新对话）。
 */
class QuickFillSessionTest {

    private fun snap(title: String?, vararg msgs: Pair<String, String>): ChatSnapshot =
        ChatSnapshot(title = title, messages = msgs.map { Msg(it.first, it.second) })

    @Before
    fun reset() {
        QuickFillSession.clear()
    }

    @Test
    fun emptySessionHasNothingToFill() {
        assertNull(QuickFillSession.next())
        assertEquals(0, QuickFillSession.size())
        assertEquals(0 to 0, QuickFillSession.position())
    }

    @Test
    fun nextCyclesThenWrapsBackToTheFirst() {
        QuickFillSession.reset("sig", listOf("第一句", "第二句", "第三句"))
        assertEquals(Triple("第一句", 1, 3), QuickFillSession.next())
        assertEquals(Triple("第二句", 2, 3), QuickFillSession.next())
        assertEquals(Triple("第三句", 3, 3), QuickFillSession.next())
        // 第 4 次点击回到第 1 条，不重新生成（按定稿）
        assertEquals(Triple("第一句", 1, 3), QuickFillSession.next())
        assertEquals(Triple("第二句", 2, 3), QuickFillSession.next())
    }

    @Test
    fun signatureChangesWhenTheOtherPersonSendsSomethingNew() {
        val before = QuickFillSession.signatureOf(snap("张三", "me" to "在吗", "other" to "在的"))
        val after = QuickFillSession.signatureOf(
            snap("张三", "me" to "在吗", "other" to "在的", "other" to "晚上吃什么")
        )
        assertFalse(before == after)
    }

    @Test
    fun signatureStaysForTheSameThread() {
        val a = QuickFillSession.signatureOf(snap("张三", "me" to "在吗", "other" to "在的"))
        val b = QuickFillSession.signatureOf(snap("张三", "me" to "在吗", "other" to "在的"))
        assertEquals(a, b)
    }

    @Test
    fun signatureDistinguishesDifferentConversations() {
        val a = QuickFillSession.signatureOf(snap("张三", "other" to "在的"))
        val b = QuickFillSession.signatureOf(snap("李四", "other" to "在的"))
        assertFalse(a == b)
    }

    @Test
    fun matchesOnlyForTheSameRound() {
        val here = QuickFillSession.signatureOf(snap("张三", "other" to "在的"))
        QuickFillSession.reset(here, listOf("好呀", "这就来"))
        assertTrue(QuickFillSession.matches(here))
        val other = QuickFillSession.signatureOf(snap("李四", "other" to "在的"))
        assertFalse(QuickFillSession.matches(other))
    }

    @Test
    fun clearDropsCandidatesAndFingerprint() {
        QuickFillSession.reset("sig", listOf("好呀"))
        QuickFillSession.clear()
        assertEquals(0, QuickFillSession.size())
        assertFalse(QuickFillSession.matches("sig"))
        assertNull(QuickFillSession.next())
        assertNull(QuickFillSession.currentText())
    }

    @Test
    fun resetStartsAgainFromTheFirstCandidate() {
        QuickFillSession.reset("sig", listOf("一", "二"))
        QuickFillSession.next()
        QuickFillSession.next()
        QuickFillSession.reset("sig2", listOf("新一", "新二"))
        assertEquals(Triple("新一", 1, 2), QuickFillSession.next())
    }

    @Test
    fun positionTracksTheCurrentCandidate() {
        QuickFillSession.reset("sig", listOf("一", "二", "三"))
        QuickFillSession.next()
        assertEquals(1 to 3, QuickFillSession.position())
        assertEquals("一", QuickFillSession.currentText())
        QuickFillSession.next()
        assertEquals(2 to 3, QuickFillSession.position())
        assertEquals("二", QuickFillSession.currentText())
    }
}
