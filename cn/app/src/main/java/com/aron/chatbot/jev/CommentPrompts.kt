package com.aron.chatbot.jev

/**
 * 「按内容写回复」的提示词（推特那一套），和 [ReplyClient] 的聊天版并列。
 * 两套提示词按来源分流：「其他」App 走聊天版（看最近对话），推特走这一套。
 *
 * 推特按整页来，不按「最后一句」来：帖子页要把帖子正文和屏幕上的回复都算上，
 * 私信页把整段对话都算上。模型拿到的是整个页面的内容，不做只看最后一条的截取。
 *
 * 为什么不区分私信和帖子：用户要的是「看内容说人话」，帖子就是主对象、
 * 已有回复是上下文；私信里对方的话也一样能读。所以这里只有一套，
 * 输入统一变成「主内容 + 已有内容」，输出统一是 N 条候选。
 *
 * 风格做成设置项（[STYLES]），用户自己选；语言跟随原帖。
 */
object CommentPrompts {

    /** 可选风格。值存进 Prefs，键保持不变，以后加风格只追加在这里。 */
    const val STYLE_ALL = "all"          // 都有：一条接观点、一条补充自己看法、一条追问或质疑
    const val STYLE_AGREE = "agree"      // 温和友好，不抬杠
    const val STYLE_OPINION = "opinion"  // 有观点，敢表态，可质疑
    const val STYLE_HUMOR = "humor"      // 幽默玩梗，但不冒犯

    /** (值, 设置页显示名, 给模型的一段说明) */
    val STYLES: List<Triple<String, String, String>> = listOf(
        Triple(STYLE_ALL, "混合（默认）",
            "各条角度要有区别：一条接住作者观点的具体回应、一条补充自己的经验或反例、一条追问或提出不同看法。"),
        Triple(STYLE_AGREE, "温和友好",
            "整体语气温和友好，认同为主，想要补充的地方也顺着说，不唱反调。"),
        Triple(STYLE_OPINION, "有观点",
            "每条都要有明确的个人观点，可以质疑、反驳或提出不同判断，但说理不说人，不人身攻击。"),
        Triple(STYLE_HUMOR, "幽默玩梗",
            "尽量轻松幽默、有点网络感，可以玩梗，但不能冒犯作者或任何群体，不能开地图炮。")
    )

    fun styleLabel(value: String): String =
        STYLES.firstOrNull { it.first == value }?.second ?: STYLES[0].second

    private fun styleNote(value: String): String =
        STYLES.firstOrNull { it.first == value }?.third ?: STYLES[0].third

    /**
     * system 提示词。结构对齐 [ReplyClient] 的聊天版：先定身份，
     * 再说每条的差异要求，再说每条必须做到的两件事，最后给格式与禁止项。
     */
    fun system(style: String, n: Int): String {
        val countRule = if (n > 1) styleNote(style) else "给出最自然、最像真人会说的一条。"
        return "你是一个评论回复助手，负责根据整个页面的内容（帖子正文和屏幕上的回复，或整段对话）来分析并写出可以公开发表的评论。" +
            "$countRule" +
            "每条评论必须做两件事：第一，对内容有具体回应——提到它说的那个点、那个例子或它的立场，" +
            "不能是放之四海皆准的空话；第二，表达你自己的观点，赞同、补充、质疑、举例、提醒都行，" +
            "不要只是把原文换个说法复述一遍。" +
            "语言跟随原文：原文是英文就用自然的英文评论，是中文就用中文评论。" +
            "每条不超过 40 字（英文不超过 30 个单词），口语，像真人在评论区说话。" +
            "不要客套话（如“说得好”“学习了”“感谢分享”），不要堆表情符号，不要加话题标签，" +
            "不要 @ 任何人，不要编造数据或事实。" +
            "只输出一个 JSON 数组，含且仅含 $n 条评论文本。" +
            "不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
    }

    /**
     * user 提示词。整页内容都给出来——主内容（帖子正文 / 对话开头）放最前面并
     * 明确标出来，页面上的其余内容（评论区 / 后续消息）跟在后面；[avoid] 用于「换一组」。
     */
    fun user(
        mainContent: String,
        author: String?,
        surroundings: List<String>,
        n: Int,
        avoid: List<String>
    ): String {
        val sb = StringBuilder()
        if (!author.isNullOrBlank()) sb.append("作者：").append(author).append("\n\n")
        sb.append("主内容：\n").append(mainContent.trim()).append("\n\n")
        if (surroundings.isNotEmpty()) {
            sb.append("已有回复（供参考，不要重复别人说过的）：\n")
            surroundings.forEach { sb.append("- ").append(it).append('\n') }
            sb.append('\n')
        }
        if (avoid.isNotEmpty()) {
            sb.append("下面这些评论已经给过，不要重复，也不要只是换个说法：\n")
            avoid.forEach { sb.append("- ").append(it).append('\n') }
            sb.append('\n')
        }
        sb.append("请给出 $n 条评论。")
        return sb.toString()
    }
}
