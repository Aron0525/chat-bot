package com.jev.probe.update

/**
 * 在线更新的唯一配置点。想换更新源（比如以后自己的仓库）只改这里，
 * API 地址和 Release 页地址都由 REPO_OWNER / REPO_NAME 推导，别处不要再写死仓库。
 *
 * 默认指向本 App 自己的仓库：更新检查走它的 GitHub Releases，
 * 每个 Release 里带一个 .apk 附件就能被 App 下载安装。
 */
object UpdateConfig {
    const val REPO_OWNER = "Aron0525"
    const val REPO_NAME = "chat-bot"

    /** GitHub「最新正式版」接口。未鉴权时每个 IP 每小时 60 次，够用；限流了界面会给提示。 */
    const val API_LATEST_URL: String =
        "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest"

    /** Release 列表页（人看的），查不到新版或没有附件时让用户自己去下。 */
    const val RELEASES_PAGE_URL: String =
        "https://github.com/" + REPO_OWNER + "/" + REPO_NAME + "/releases"

    /** FileProvider 的 authority，必须和 AndroidManifest.xml 里的 ${applicationId}.update.fileprovider 一致。 */
    const val AUTHORITY = "com.jev.probe.update.fileprovider"

    /** APK 的 MIME，交给系统安装器时用。 */
    const val APK_MIME = "application/vnd.android.package-archive"

    /** 静默检查间隔：最多每 6 小时打一次接口，避免每次打开 App 都去查。 */
    const val SILENT_CHECK_INTERVAL_MS: Long = 6L * 60 * 60 * 1000

    /** 下载状态和上次检查时间存放在这个私有配置文件里，与业务设置（Prefs）分开。 */
    const val STORE_NAME = "jev_update"
}