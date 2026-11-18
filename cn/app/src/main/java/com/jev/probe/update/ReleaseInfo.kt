package com.jev.probe.update

/**
 * 从 GitHub Releases 的 JSON 里解析出的一个发布版。
 * 只保留在线更新真正用得上的几个字段，其余忽略。
 */
data class ReleaseInfo(
    /** 原始 tag，例如 "v1.8"；展示时用它，用户看的是 GitHub 上那个名字。 */
    val tag: String,
    /** 去掉 v 前缀的版本号，例如 "1.8"，用于和本机版本比较。 */
    val versionName: String,
    /** Release 说明（Markdown 原文），弹窗里展示「这个版本改了什么」。 */
    val notes: String,
    /** 这个 Release 的网页地址，用于「查看说明 / 去手动下载」。 */
    val pageUrl: String,
    /** .apk 附件的下载地址；Release 没带 apk 附件时为空串。 */
    val apkUrl: String,
    /** .apk 附件的文件名，下载到本地时用它命名。 */
    val apkName: String,
    /** .apk 的字节数；接口没给就是 0，下载时就不做大小校验。 */
    val apkSize: Long
) {
    /** Release 里到底有没有可以直接下载安装的 apk。 */
    val hasApk: Boolean get() = apkUrl.isNotBlank()
}

/**
 * 检查结果。失败只带一句人话，绝不把异常抛到 UI 层。
 */
sealed class CheckOutcome {
    /** 本机版本不比线上旧。latest 为 null 表示根本没解析出可用的 Release。 */
    data class UpToDate(val latest: ReleaseInfo?) : CheckOutcome()

    /** 线上版本严格比本机新，可以提示更新。 */
    data class Newer(val release: ReleaseInfo) : CheckOutcome()

    /** 查不到：网络 / 限流 / 仓库没有 Release / 接口异常，reason 是给人看的话。 */
    data class Failed(val reason: String) : CheckOutcome()
}