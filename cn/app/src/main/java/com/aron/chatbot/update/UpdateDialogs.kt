package com.aron.chatbot.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

/**
 * 更新流程的对话框壳。Activity 订阅 [UpdateCenter] 后把状态原样交给它，
 * 它决定这一屏该显示什么。所有调用都发生在主线程。
 *
 * 两种模式：
 * - [Mode.MANUAL]：用户主动点了「检查更新」，所以什么都反馈（已是最新、失败也说清楚）；
 * - [Mode.SILENT]：App 启动时的后台检查，只有真发现新版才弹，绝不打扰。
 */
class UpdateDialogs(
    private val activity: Activity,
    private val mode: Mode = Mode.MANUAL
) {
    enum class Mode { MANUAL, SILENT }

    private var dialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    /** 状态入口。 */
    fun onState(state: UpdateCenter.State) {
        when (state) {
            is UpdateCenter.State.Available -> showAvailable(state.release)
            is UpdateCenter.State.Downloading -> showOrUpdateProgress(state)
            is UpdateCenter.State.Ready -> showReady(state.apk, state.release)
            is UpdateCenter.State.Failed -> if (mode == Mode.MANUAL) showFailed(state.reason)
            is UpdateCenter.State.UpToDate -> if (mode == Mode.MANUAL) showUpToDate(state.latest)
            UpdateCenter.State.Checking, UpdateCenter.State.Idle -> Unit
        }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        progressBar = null
        progressText = null
    }

    // -------------------------------------------------------------- 各状态

    private fun showAvailable(release: ReleaseInfo) {
        dismiss()
        val current = UpdateCenter.currentVersionName(activity)
        val sb = StringBuilder()
        sb.append("当前版本 ").append(current).append("\n")
        sb.append("最新版本 ").append(release.tag)
        if (release.hasApk && release.apkSize > 0L) {
            sb.append("\n安装包 ").append(UpdateFormat.bytes(release.apkSize))
        }
        sb.append("\n\n")
        if (release.hasApk) {
            sb.append("下载后交给系统安装器安装：不会自动安装，也不会动你的任何设置。")
        } else {
            sb.append("这个版本没有提供 apk 附件，只能在 Release 页手动下载。")
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("发现新版本 " + release.tag)
            .setMessage(sb.toString())
            .setNegativeButton("以后再说", null)
        if (release.hasApk) {
            builder.setPositiveButton("下载并安装") { _, _ ->
                UpdateCenter.download(activity, release)
            }
            builder.setNeutralButton("查看说明") { _, _ -> openPage(release.pageUrl) }
        } else {
            builder.setPositiveButton("去 Release 页") { _, _ -> openPage(release.pageUrl) }
        }
        dialog = builder.show()
    }

    private fun showOrUpdateProgress(state: UpdateCenter.State.Downloading) {
        if (progressBar == null) {
            dismiss()
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(18), dp(24), dp(4))
            }
            val label = TextView(activity).apply { text = "连接下载服务器…" }
            val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            box.addView(label)
            box.addView(bar)
            progressText = label
            progressBar = bar
            dialog = AlertDialog.Builder(activity)
                .setTitle("正在下载 " + state.release.tag)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消") { _, _ -> UpdateCenter.cancelDownload() }
                .show()
            progressText?.text = UpdateFormat.progress(state.done, state.total)
        } else {
            val pct = if (state.total > 0L) (state.done * 100 / state.total).toInt() else 0
            progressBar?.progress = pct.coerceIn(0, 100)
            progressText?.text = UpdateFormat.progress(state.done, state.total)
        }
    }

    private fun showReady(apk: java.io.File, release: ReleaseInfo) {
        dismiss()
        if (!ApkInstaller.canRequestInstalls(activity)) {
            dialog = AlertDialog.Builder(activity)
                .setTitle("需要允许安装应用")
                .setMessage(
                    "系统要求先允许「安装未知应用」才能装这个更新。\n\n" +
                        "点「去设置」打开开关，回来后在这里再点一次「立即安装」。"
                )
                .setPositiveButton("去设置") { _, _ -> ApkInstaller.requestInstallPermission(activity) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("下载完成")
            .setMessage(release.tag + " 已下载完成。点「立即安装」交给系统安装器；安装过程中 App 会退出，装完再打开就是新版本。")
            .setPositiveButton("立即安装") { _, _ -> ApkInstaller.install(activity, apk) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun showFailed(reason: String) {
        dismiss()
        dialog = AlertDialog.Builder(activity)
            .setTitle("检查更新失败")
            .setMessage(reason)
            .setPositiveButton("好", null)
            .setNeutralButton("打开 Release 页") { _, _ -> openPage(UpdateConfig.RELEASES_PAGE_URL) }
            .show()
    }

    private fun showUpToDate(latest: ReleaseInfo?) {
        dismiss()
        if (latest == null) {
            dialog = AlertDialog.Builder(activity)
                .setTitle("检查更新")
                .setMessage("没有取到可用的发布版本，可能这个仓库还没发布过 Release。")
                .setPositiveButton("好", null)
                .show()
            return
        }
        val current = UpdateCenter.currentVersionName(activity)
        val comparable = UpdateVersion.parse(current) != null &&
            UpdateVersion.parse(latest.versionName) != null
        val msg = if (comparable) {
            "当前版本 " + current + " 已经是最新。"
        } else {
            "当前版本 " + current + " 没法参与版本比较（不是纯数字版本号）。\n\n最新发布是 " + latest.tag + "。"
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (comparable) "已是最新版本" else "最新发布 " + latest.tag)
            .setMessage(msg)
            .setPositiveButton("好", null)
        if (!comparable && latest.hasApk) {
            builder.setNeutralButton("下载这个版本") { _, _ ->
                UpdateCenter.download(activity, latest)
            }
        }
        dialog = builder.show()
    }

    // --------------------------------------------------------------

    private fun openPage(url: String) {
        if (url.isBlank()) return
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics
    ).roundToInt()
}
