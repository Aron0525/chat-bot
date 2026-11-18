package com.jev.probe.core

/**
 * 「一键直填」那套方案的一轮状态：同一段对话里已经生成好的候选，以及现在轮到第几条。
 *
 * 它只回答两件事：
 * 1. 现在这次点击，还能不能复用上一轮的候选（[matches]）；
 * 2. 下一条该填什么（[next]）。
 *
 * 对方发来新消息、或者换了会话，[signature] 就对不上了，于是整轮作废——
 * 重新分析，而不是拿旧回复去套新对话。
 */
object QuickFillSession {

    /** 一轮的指纹：会话标题 + 最后一条消息 + 消息条数，足够判断「是不是同一轮对话」。 */
    fun signatureOf(snapshot: ChatSnapshot): String {
        val last = snapshot.messages.lastOrNull()
        return (snapshot.title ?: "") + "|" + snapshot.messages.size + "|" +
            (last?.side ?: "") + "|" + (last?.text ?: "")
    }

    private var signature: String? = null
    private var candidates: List<String> = emptyList()
    private var index: Int = -1

    /** 当前这一轮对得上，且有可填的内容。 */
    fun matches(sig: String): Boolean = candidates.isNotEmpty() && signature == sig

    /** 装上新的一轮候选，并把指针停在 -1（下一次 [next] 从第 1 条开始）。 */
    fun reset(sig: String, list: List<String>) {
        signature = sig
        candidates = list
        index = -1
    }

    fun clear() {
        signature = null
        candidates = emptyList()
        index = -1
    }

    /**
     * 取下一条。到末尾就回到第 1 条循环（按定稿：三条点完不重新生成），
     * 返回 (要填的文本, 第几条 从 1 开始, 共几条)；没有候选时返回 null。
     */
    fun next(): Triple<String, Int, Int>? {
        if (candidates.isEmpty()) return null
        index = (index + 1) % candidates.size
        return Triple(candidates[index], index + 1, candidates.size)
    }

    /** 当前条目的文本（球上做提示用），没有就是 null。 */
    fun currentText(): String? = candidates.getOrNull(index)

    /** 当前是第几条 / 共几条；没开始是 0 / 0。 */
    fun position(): Pair<Int, Int> = (index + 1) to candidates.size

    fun size(): Int = candidates.size
}
