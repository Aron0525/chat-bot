package com.jev.probe.update

import java.util.Locale

/**
 * 更新界面用到的纯格式化。抽成不带 Android 依赖的 object，方便用 JVM 单测覆盖。
 */
object UpdateFormat {

    /** 0 → "未知大小"；1234567 → "1.2 MB"；512 → "512 B"。 */
    fun bytes(n: Long): String = when {
        n <= 0L -> "未知大小"
        n >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
        n >= 1024L -> String.format(Locale.US, "%.0f KB", n / 1024.0)
        else -> n.toString() + " B"
    }

    /** "3.4 MB / 12.0 MB（28%）"；total 未知时只报已下字节。 */
    fun progress(done: Long, total: Long): String {
        return if (total > 0L) {
            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
            bytes(done) + " / " + bytes(total) + "（" + pct + "%）"
        } else {
            bytes(done)
        }
    }
}