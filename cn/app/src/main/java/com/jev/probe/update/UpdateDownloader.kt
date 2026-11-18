package com.jev.probe.update

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把 Release 里的 .apk 下到应用私有缓存目录（cacheDir/update/）。
 *
 * 进度走回调，支持取消（[cancel] 返回 true 时停止）；失败抛 IOException，
 * 由 [UpdateCenter] 统一翻译成人话。下完做两道自检：大小对不对、文件头是不是 ZIP
 * （.apk 就是 zip），免得把一个错误页/半截文件交给系统安装器。
 */
class UpdateDownloader(private val context: Context) {

    /** 用户点了取消。UpdateCenter 据此把状态退回到「有新版本」，不弹失败框。 */
    class Cancelled : RuntimeException("下载已取消")

    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * 下载 [release] 指向的 apk，返回落地的文件。
     * [onProgress] 在下载线程被调用（UI 更新由调用方自己切回主线程）。
     */
    fun download(
        release: ReleaseInfo,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit
    ): File {
        if (!release.hasApk) throw IOException("这个版本没有提供安装包下载地址")
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val fileName = release.apkName.ifBlank { "jev-update.apk" }
        val target = File(dir, fileName)
        val partial = File(dir, fileName + ".part")

        var conn: HttpURLConnection? = null
        var url = release.apkUrl
        var hops = 0
        try {
            while (true) {
                if (cancelled) throw Cancelled()
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", USER_AGENT)

                val code = c.responseCode
                if (code in 301..308) {
                    val loc = c.getHeaderField("Location")
                    if (loc.isNullOrBlank()) throw IOException("下载地址重定向异常")
                    if (++hops > 5) throw IOException("下载地址重定向次数过多")
                    url = loc
                    c.disconnect()
                    conn = null
                    continue
                }
                if (code != 200) throw IOException("下载失败：HTTP " + code)

                val total = c.contentLengthLong
                var done = 0L
                val input = c.inputStream
                partial.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (cancelled) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
                input.close()

                verify(partial, total)
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) throw IOException("下载文件无法保存到本地")
                Log.i(TAG, "downloaded " + target.name + " " + target.length() + " bytes")
                return target
            }
        } finally {
            conn?.disconnect()
        }
    }

    /** 大小一致（接口给了 size 时）且文件头是 "PK"（.apk 是 zip）。 */
    private fun verify(file: File, expected: Long) {
        if (expected > 0L && file.length() != expected) {
            throw IOException(
                "下载不完整（" + file.length() + "/" + expected + " 字节），请重试"
            )
        }
        file.inputStream().use { ins ->
            val head = ByteArray(2)
            val n = ins.read(head)
            if (n < 2 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                throw IOException("下载到的文件不是有效的安装包，请重试")
            }
        }
    }

    companion object {
        private const val TAG = "UpdateDownloader"
        private const val USER_AGENT = "JevUpdate/1.0 (Android)"
    }
}