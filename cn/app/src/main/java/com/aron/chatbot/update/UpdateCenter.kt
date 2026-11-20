package com.aron.chatbot.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 在线更新的唯一状态机，全 App 共用一个实例。
 *
 * 为什么不让 Activity 自己开线程：检查/下载都在这一个单线程执行器上跑，
 * 界面转屏、退到后台都不会把下载干掉；Activity 只订阅状态（[addListener]），
 * 重建后 [state] 还在，界面照着当前状态画就行。
 *
 * 所有状态回调都切到主线程，订阅方可以直接动 View。
 */
object UpdateCenter {

    private const val TAG = "UpdateCenter"

    /** 更新流程的状态。UI 是它的纯函数：同一个状态永远画成同一样子。 */
    sealed class State {
        /** 什么都没做。 */
        object Idle : State()

        /** 正在查接口。 */
        object Checking : State()

        /** 已经是最新（或本机版本号没法比较）。latest 可能为 null。 */
        data class UpToDate(val latest: ReleaseInfo?) : State()

        /** 发现新版，等用户决定装不装。 */
        data class Available(val release: ReleaseInfo) : State()

        /** 正在下载。total 为 0 表示服务器没给大小。 */
        data class Downloading(val done: Long, val total: Long, val release: ReleaseInfo) : State()

        /** 下载完成并通过自检，等用户点安装。 */
        data class Ready(val apk: File, val release: ReleaseInfo) : State()

        /** 失败了，reason 是给人看的一句话。 */
        data class Failed(val reason: String) : State()
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jev-update").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<(State) -> Unit>()
    private var downloader: UpdateDownloader? = null

    /** 当前状态。订阅方进页面时先读它把界面画对，再收后续变化。 */
    @Volatile
    private var current: State = State.Idle

    val state: State get() = current

    // ------------------------------------------------------------ 订阅

    /** 订阅状态变化；注册时会把当前状态立刻补发一次，免得界面漏掉。 */
    fun addListener(listener: (State) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
        val now = current
        main.post { listener(now) }
    }

    fun removeListener(listener: (State) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun publish(next: State) {
        current = next
        val snapshot: List<(State) -> Unit> = synchronized(listeners) { listeners.toList() }
        if (snapshot.isEmpty()) return
        main.post {
            for (l in snapshot) l(next)
        }
    }

    // ------------------------------------------------------------ 动作

    /** 查一次更新。正在查/正在下载时直接忽略，避免连点把接口打爆。 */
    fun check(context: Context) {
        val s = current
        if (s is State.Checking || s is State.Downloading) return
        val appContext = context.applicationContext
        val currentVersion = currentVersionName(appContext)
        markChecked(appContext)
        publish(State.Checking)
        executor.execute {
            val outcome = try {
                UpdateChecker.check(currentVersion)
            } catch (e: Exception) {
                CheckOutcome.Failed(describe(e))
            }
            publish(
                when (outcome) {
                    is CheckOutcome.Newer -> State.Available(outcome.release)
                    is CheckOutcome.UpToDate -> State.UpToDate(outcome.latest)
                    is CheckOutcome.Failed -> State.Failed(outcome.reason)
                }
            )
        }
    }

    /** 下载 [release] 的 apk。已有下载在跑就忽略。 */
    fun download(context: Context, release: ReleaseInfo) {
        if (current is State.Downloading) return
        val appContext = context.applicationContext
        val d = UpdateDownloader(appContext)
        downloader = d
        publish(State.Downloading(0L, release.apkSize, release))
        executor.execute {
            try {
                val apk = d.download(release) { done, total ->
                    publish(State.Downloading(done, total, release))
                }
                downloader = null
                publish(State.Ready(apk, release))
            } catch (e: UpdateDownloader.Cancelled) {
                downloader = null
                // 取消不算失败：退回「发现新版」，用户还能再点一次
                publish(State.Available(release))
            } catch (e: Exception) {
                downloader = null
                Log.e(TAG, "download failed", e)
                publish(State.Failed(describe(e)))
            }
        }
    }

    /** 取消进行中的下载。 */
    fun cancelDownload() {
        downloader?.cancel()
    }

    /** 把状态拉回 Idle（对话框关掉后调用，避免下次进页面还弹旧的）。 */
    fun reset() {
        if (current is State.Downloading) return
        publish(State.Idle)
    }

    // ------------------------------------------------------------ 杂项

    /** 把底层异常翻成用户能动手的人话。下载失败 / 检查失败都走这里。 */
    private fun describe(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            m.isBlank() -> "更新失败：" + e.javaClass.simpleName
            m.contains("UnknownHost", ignoreCase = true) -> "域名解析失败，检查网络连接"
            m.contains("timeout", ignoreCase = true) -> "连接超时，请检查网络"
            m.contains("HTTP 403", ignoreCase = true) || m.contains("HTTP 429", ignoreCase = true) ->
                "GitHub 限流或网络受限（HTTP 403），换网络（开代理或切流量）再试"
            m.contains("HTTP 404", ignoreCase = true) -> "下载地址失效（HTTP 404），这个版本可能已被删除"
            m.contains("SSL", ignoreCase = true) || m.contains("Cert", ignoreCase = true) ->
                "HTTPS 证书校验失败"
            else -> m
        }
    }

    /** 本机版本名（用户看到的是它，比如 "1.7"）。取不到就给个不会被当成新版的占位。 */
    fun currentVersionName(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "0.0.0"
    } catch (e: Exception) {
        Log.w(TAG, "cannot read versionName", e)
        "0.0.0"
    }

    /** 静默检查的节流：距上次检查够久才查。返回 true 表示这次该查。 */
    fun shouldSilentCheck(context: Context): Boolean {
        val sp = context.getSharedPreferences(UpdateConfig.STORE_NAME, Context.MODE_PRIVATE)
        val last = sp.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        return now - last >= UpdateConfig.SILENT_CHECK_INTERVAL_MS
    }

    private fun markChecked(context: Context) {
        context.getSharedPreferences(UpdateConfig.STORE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    private const val KEY_LAST_CHECK = "last_check_ms"
}
