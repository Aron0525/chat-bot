package com.aron.chatbot.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载好的 apk 交给系统安装器。
 *
 * 只负责「拉起安装界面」，**从不自动安装、从不点任何系统按钮** ——
 * 和本项目「人拍板」的硬约束一致：装不装、什么时候装，由用户自己点。
 */
object ApkInstaller {

    /**
     * Android 8（API 26）起，装别的来源的 apk 要先允许「安装未知应用」。
     * 本项目 minSdk 30，所以只需要问系统当前有没有这个授权。
     */
    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** 跳到系统的「安装未知应用」开关页，回来后用户再点一次「立即安装」。 */
    fun requestInstallPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + activity.packageName)
        )
        runCatching { activity.startActivity(intent) }
    }

    /** 用 FileProvider 把私有目录里的 apk 分享给系统安装器。 */
    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, UpdateConfig.AUTHORITY, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, UpdateConfig.APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }.onFailure {
            android.widget.Toast.makeText(activity, "打不开系统安装器", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}