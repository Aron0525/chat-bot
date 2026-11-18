package com.aron.chatbot.jev

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推特那套评论提示词。锁住几条产品要求：
 * 每条既要回应内容、又要有自己的观点；语言跟随原帖；禁止客套话和话题标签；
 * 风格要真的能换。
 */
class CommentPromptsTest {

    @Test
    fun systemRequiresBothReplyAndOpinion() {
        val s = CommentPrompts.system(CommentPrompts.STYLE_ALL, 3)
        assertTrue(s, s.contains("评论回复助手"))
        assertTrue(s, s.contains("对内容有具体回应"))
        assertTrue(s, s.contains("表达你自己的观点"))
        assertTrue(s, s.contains("语言跟随原文"))
        assertTrue(s, s.contains("JSON 数组"))
    }

    @Test
    fun systemCarriesRequestedCount() {
        assertTrue(CommentPrompts.system(CommentPrompts.STYLE_ALL, 1).contains("含且仅含 1 条"))
        assertTrue(CommentPrompts.system(CommentPrompts.STYLE_ALL, 3).contains("含且仅含 3 条"))
    }

    @Test
    fun systemForbidsTheUsualCommentSectionNoise() {
        val s = CommentPrompts.system(CommentPrompts.STYLE_ALL, 2)
        assertTrue(s, s.contains("客套话"))
        assertTrue(s, s.contains("话题标签"))
        assertTrue(s, s.contains("不要 @"))
    }

    @Test
    fun eachStyleProducesADifferentInstruction() {
        val texts = CommentPrompts.STYLES.map { CommentPrompts.system(it.first, 3) }.toSet()
        // 4 种风格必须真的不一样，否则设置里选了没效果
        assertTrue("styles collapsed into fewer variants", texts.size == CommentPrompts.STYLES.size)
    }

    @Test
    fun singleCandidateSkipsTheVarietyRule() {
        val s = CommentPrompts.system(CommentPrompts.STYLE_ALL, 1)
        assertFalse(s, s.contains("各条角度要有区别"))
        assertTrue(s, s.contains("最自然"))
    }

    @Test
    fun userPromptPutsMainContentFirstAndLabelsSurroundings() {
        val u = CommentPrompts.user(
            mainContent = "远程办公效率更高还是更低",
            author = "某作者 @someone",
            surroundings = listOf("我觉得看岗位", "回复 @a 同意"),
            n = 3,
            avoid = emptyList()
        )
        assertTrue(u, u.contains("作者：某作者 @someone"))
        assertTrue(u, u.contains("主内容："))
        assertTrue(u, u.contains("远程办公效率更高还是更低"))
        assertTrue(u, u.contains("已有回复"))
        assertTrue(u, u.contains("我觉得看岗位"))
        assertTrue(u, u.contains("请给出 3 条评论"))
    }

    @Test
    fun userPromptCarriesAvoidListForRegeneration() {
        val u = CommentPrompts.user("帖子", null, emptyList(), 2, listOf("说过了这句"))
        assertTrue(u, u.contains("已经给过"))
        assertTrue(u, u.contains("说过了这句"))
    }

    @Test
    fun userPromptOmitsEmptySections() {
        val u = CommentPrompts.user("只有正文", null, emptyList(), 1, emptyList())
        assertFalse(u, u.contains("作者："))
        assertFalse(u, u.contains("已有回复"))
        assertFalse(u, u.contains("已经给过"))
    }

    @Test
    fun unknownStyleFallsBackInsteadOfCrashing() {
        val s = CommentPrompts.system("不存在的风格", 3)
        assertTrue(s, s.contains("评论回复助手"))
        assertTrue(CommentPrompts.styleLabel("不存在的风格").isNotEmpty())
    }
}
