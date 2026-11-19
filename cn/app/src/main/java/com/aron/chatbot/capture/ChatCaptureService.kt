package com.aron.chatbot.capture

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aron.chatbot.capture.ocr.MlKitOcr
import com.aron.chatbot.capture.ocr.OcrLine
import com.aron.chatbot.capture.ocr.ScreenCapture
import com.aron.chatbot.core.BubbleRect
import com.aron.chatbot.core.ChatSnapshot
import com.aron.chatbot.core.Msg
import com.aron.chatbot.core.Prefs
import com.aron.chatbot.core.QuickFillSession
import com.aron.chatbot.core.kb.ContextBuilder
import com.aron.chatbot.core.kb.KbStore
import com.aron.chatbot.jev.JevClient
import com.aron.chatbot.overlay.OverlayController
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The live capture service. It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Judge/reply HTTP runs here under a hard watchdog (see [withHardTimeout]).
     *  Cached so an abandoned request — interrupts do not break a wedged socket
     *  read — never blocks the next round's threads. */
    private val analysisPool = Executors.newCachedThreadPool { r ->
        Thread(r, "chatbot-http").apply { isDaemon = true }
    }

    /**
     * Run [body] on [analysisPool] but never wait longer than [ms].
     *
     * HttpURLConnection bounds connect/read per attempt yet NOT DNS resolution
     * (and some OEM stacks stall connects beyond both), so one wedged request
     * used to hold its round's callback forever: the panel stayed on 生成中 and
     * the round's [analyzing] flag never dropped, silently discarding every
     * later analysis. Past the ceiling we abandon the attempt and surface a
     * plain, retryable error instead.
     */
    private fun <T> withHardTimeout(ms: Long, what: String, body: () -> T): T {
        val f = analysisPool.submit(Callable { body() })
        return try {
            f.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            throw java.io.IOException("$what 超时（${ms / 1000}秒），请检查网络后重试")
        }
    }

    /** Adapted chat apps, keyed by package name. Apps in [BLOCKED_PKGS] are
     *  never read at all (see [maybeCapture] / [onAccessibilityEvent]). */
    private val adapters = listOf(QQAdapter(), XAdapter(), FeishuAdapter(), WhatsAppAdapter()).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    /** 直填模式下有一轮分析在飞：期间再点球只提示，不重复烧 token。 */
    private var quickAnalysisPending = false
    /** 最近一次认出来的会话标题，暂时读不到时用来保住卡片内容。 */
    private var lastKnownTitle: String? = null
    /** 读不到聊天页的重试计数：Compose 页面经常晚一两帧才有输入框。 */
    private var unreadableAttempts = 0
    private val unreadableRetry = Runnable { maybeCapture() }
    /** 白名单提示的节流，避免每条事件都弹一次 toast。 */
    private var whitelistNoticeShown = false

    /** 当前是不是「一键直填」模式。 */
    private val quickMode: Boolean
        get() = prefs.replyMode == Prefs.REPLY_MODE_QUICK

    private var lastSignature: String = ""
    private var activePkg: String? = null
    private var analyzing = false
    /** A "换一组" redraft is in flight (candidates only; the judgment stays). */
    private var regenerating = false
    private val session = ConversationSession()
    /** Tells new incoming messages apart from scrolling through history. */
    private val gate = NewMessageGate()
    /** The snapshot and knowledge context of the last analysis, reused by "换一组". */
    private var lastAnalyzed: ChatSnapshot? = null
    private var lastCtx: com.aron.chatbot.core.kb.ChatContext? = null
    private val analysisTasks = ArrayList<Future<*>>()
    private var destroyed = false
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" || key == "whitelist") {
            main.post {
                leaveConversation()
                overlay?.hide()
            }
        }
        // 切换填入方式：立刻按新方式收拾界面，不用等下一次事件。
        // 直填模式只留球：卡片收成球，球上进度字样清掉。
        if (key == Prefs.KEY_REPLY_MODE) {
            main.post {
                QuickFillSession.clear()
                syncBubble(rootInActiveWindow?.packageName?.toString())
                if (quickMode) {
                    overlay?.showIdle(null)
                    overlay?.setBubbleLabel(null)
                }
            }
        }
    }

    /** Invalidate callbacks before cancelling workers; interruption alone is not a guard. */
    private fun cancelAnalysis() {
        session.invalidate()
        main.removeCallbacks(debounce)
        pendingSnapshot = null
        analyzing = false
        regenerating = false
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
        overlay?.resetForNewConversation()
    }

    /** Drop the round in flight (its result would be stale) but keep what the card shows. */
    private fun cancelInFlight() {
        session.invalidate()
        main.removeCallbacks(debounce)
        analyzing = false
        regenerating = false
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
    }

    private fun observeTarget(target: ConversationSession.Target?) {
        if (session.observe(target)) {
            cancelAnalysis()
            currentSnapshot = null
            activePkg = null
            lastSignature = ""
            lastOcrSignature = ""
            gate.reset()
            lastAnalyzed = null
            lastCtx = null
        }
    }

    private fun leaveConversation() {
        observeTarget(null)
        cancelAnalysis()
        currentSnapshot = null
    }

    /** Read the live target, never the previous chat's cached/stabilized title. */
    private fun targetFor(root: AccessibilityNodeInfo): ConversationSession.Target? {
        val pkg = root.packageName?.toString() ?: return null
        if (isBlocked(pkg) || pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("launcher", true) || pkg == "com.miui.home") return null
        val adapter = adapters[pkg]
        var messagesSignature: String? = null
        val title = if (adapter != null) {
            val snapshot = adapter.extract(root, resources) ?: return null
            messagesSignature = snapshot.takeIf { it.messages.isNotEmpty() }?.signature()
            // A loading/unknown title cannot prove which conversation is open —
            // unless the SAME app+window already has a known one: animated
            // headers swap in skeleton/transient titles for a beat (#68), and
            // unbinding the session there killed in-flight analyses. Keep the
            // previously verified title instead.
            val fresh = snapshot.title?.takeUnless { isTransientTitle(it) }
            if (fresh != null) fresh
            else {
                val known = session.target
                if (known != null && known.pkg == pkg && known.windowId == root.windowId &&
                    !known.title.isNullOrBlank()) known.title else return null
            }
        } else {
            findTitleInActionBar(root, Int.MAX_VALUE, resources.displayMetrics.widthPixels,
                resources, 0.15, 0.85)
        }
        return ConversationSession.Target(pkg, root.windowId, title, messagesSignature)
    }

    private fun isCurrent(token: ConversationSession.Token): Boolean {
        if (destroyed || !prefs.enabled || !session.accepts(token)) return false
        val live = rootInActiveWindow?.let { targetFor(it) }
        // A null re-extract is a transient accessibility glitch (list animation,
        // node cache miss), NOT proof the chat was left — hiding there killed
        // freshly rendered candidates (seen on QQ). Identity checks apply
        // only when we actually got a reading.
        if (live != null && (!live.sameConversation(token.target) ||
            !prefs.isAllowed(currentSnapshot?.title ?: live.title))) {
            leaveConversation()
            overlay?.hide()
            return false
        }
        return true
    }

    /** Only called on the main thread, including the context-completion callback. */
    private fun submitAnalysis(task: () -> Unit) {
        try { analysisTasks.add(worker.submit(task)) } catch (_: RejectedExecutionException) { }
    }

    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null

    /**
     * #18: WINDOW_STATE_CHANGED blips — the notification shade, a permission
     * dialog, a split-screen handle — are transient, but the drop branch below
     * used to tear the bubble down the instant one arrived, and an open panel
     * was lost mid-read. Hide only if the screen is STILL a drop-zone after a
     * short grace period; any chat-app event in between cancels the hide.
     */
    private val confirmAwayHide = Runnable {
        val fg = rootInActiveWindow?.packageName?.toString()
        val stillAway = fg == null || fg == packageName ||
            fg.contains("launcher", ignoreCase = true) ||
            fg == "com.miui.home" || fg == "com.android.systemui"
        if (stillAway) overlay?.hide()
    }
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    /** Whether the "not supported" notice has been shown for the current visit
     *  to a blocked app. Reset whenever another foreground is seen, so it
     *  re-appears next visit but does not re-pop on every event. */
    private var blockedNoticeShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferencesListener)
        overlay = OverlayController(this)
        // 只有一颗球了：绿球（一键直填）已停用，直填模式也走这颗球，见 quickTap()
        // 手动点面板：认得出就分析，认不出就把原因说清楚（不再静默短路）。
        overlay?.onManualAnalyze = {
            // 直填模式 + 推特：点球 = 一键直填；其它情况点球 = 打开分析面板
            if (quickMode && rootInActiveWindow?.packageName?.toString() == X_PKG) quickTap()
            else analyzeOnDemand()
        }
        overlay?.onRegenerate = { regenerateReplies() }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { leaveConversation(); overlay?.hide(); return }

        val type = event.eventType
        // #18-4: our own overlay redraws ("generating…" → judgment → candidates)
        // emit WINDOW_CONTENT_CHANGED / VIEW_SCROLLED carrying our package name.
        // Reacting to them woke the service in a loop: re-read → re-analyze →
        // redraw → …, which pinned the panel on "generating" forever. Window-state
        // events keep flowing: the fg == packageName branch above still hides the
        // bubble inside our own settings screens.
        if (event.packageName?.toString() == packageName &&
            (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
             type == AccessibilityEvent.TYPE_VIEW_SCROLLED)) return
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            // 直填球跟着前台走（离开推特就收走）
            syncBubble(fg)
            // Blocked apps: never read/screenshot/OCR/fill there, only show the
            // one-time "not supported" notice and stop. Checked before the
            // generic no-adapter branch.
            if (isBlocked(fg)) { foregroundPkg = fg; showBlocked(auto = true); return }
            if (fg != null && fg !in adapters) {
                val target = rootInActiveWindow?.let { targetFor(it) }
                if (session.target != target) leaveConversation()
                foregroundPkg = fg
                blockedNoticeShown = false // left a blocked app → allow the notice again next visit
                val drop = fg == packageName ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                if (drop) {
                    // Grace period instead of an instant tear-down (see [confirmAwayHide]).
                    main.removeCallbacks(confirmAwayHide)
                    main.postDelayed(confirmAwayHide, FOREGROUND_CONFIRM_MS)
                } else {
                    main.removeCallbacks(confirmAwayHide)
                    // 未适配的应用：球留着（菜单还能进「截屏识别一次」），标成认不出
                    markReadable(false, currentUnreadableReason())
                    overlay?.showIdle(null)
                }
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        // The chat app is back (or never left): a pending hide confirmation is moot.
        main.removeCallbacks(confirmAwayHide)
        val root = rootInActiveWindow ?: run { leaveConversation(); overlay?.hide(); return }
        val pkg = root.packageName?.toString()
        // 唯一那颗球跟着前台走：可用的第三方应用上都摆着，桌面/自己/屏蔽应用上不摆
        syncBubble(pkg)
        // Blocked apps — no tree read, no screenshot, no OCR, no fill. A
        // content-changed / scrolled event there only re-shows the one-time
        // notice (deduped); it must never reach an adapter or the OCR path.
        if (isBlocked(pkg)) { showBlocked(auto = true); return }
        blockedNoticeShown = false // any other foreground → allow the notice again next visit
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: run {
            if (session.target != null && session.target != targetFor(root)) leaveConversation()
            markReadable(false, currentUnreadableReason())
            return
        }
        // Outside a chat window (the conversation list, a profile, settings…) the
        // adapter returns null. That is NOT a reason to show nothing: an adapted
        // app must behave at least as well as an unadapted one, which parks an idle
        // bubble so the menu stays reachable. Without this, opening QQ / Feishu on
        // their list screen produced no bubble at all.
        val rawSnapshot = adapter.extract(root, resources)
        if (rawSnapshot == null) { retryWhenUnreadable(); return }
        val target = targetFor(root)
        if (target == null) { retryWhenUnreadable(); return }
        unreadableAttempts = 0
        main.removeCallbacks(unreadableRetry)
        main.removeCallbacks(confirmLeave)
        observeTarget(target)
        overlay?.setApp(pkg)
        // 认出来了：球保持蓝色
        markReadable(true)
        // Use only this window's title; never inherit another conversation's title.
        val snapshot = rawSnapshot
        if (!snapshot.title.isNullOrBlank()) lastKnownTitle = snapshot.title
        if (!prefs.isAllowed(snapshot.title)) {
            // 以前这里是静默 hide()：面板凭空消失，用户只会以为 App 坏了。
            // 现在球留着并标成认不出，点一下就说清「不在白名单」。
            noWhitelistNotice()
            leaveConversation()
            markReadable(false, "这个会话不在白名单里，去设置里加上它的标题")
            overlay?.showIdle(null)
            return
        }
        // 直填模式：只在推特上生效——不自动分析、不显示卡片，只有用户点球才分析。
        // 其它 App（QQ/飞书/WhatsApp）照旧走卡片那套，两套互不干扰。
        // 注意这里是「卡片收成球」而不是 hide()——hide 会把唯一的球也收走。
        if (quickMode && pkg == X_PKG) {
            currentSnapshot = snapshot.withSource(pkg)
            if (QuickFillSession.size() > 0 &&
                !QuickFillSession.matches(QuickFillSession.signatureOf(snapshot))) {
                // 对方发了新消息 / 换了会话：这一轮的候选作废，别再拿旧回复去套
                QuickFillSession.clear()
                overlay?.setReadState(OverlayController.ReadState.READY)
            }
            overlay?.showIdle(null)
            return
        }
        // In a chat window but the tree holds no text (Feishu draws its bodies)
        // → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            // In a chat window, but the tree carries no text (Feishu draws its
            // message bodies). Park the bubble BEFORE attempting OCR, so the user
            // still has something to tap when OCR is off, deduped, or comes back
            // empty — previously all three cases left the screen with no bubble.
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            if (prefs.ocrFallback && pkg !in NO_SCREENSHOT_PKGS) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                if (ocrBusy) return
                lastOcrSignature = sig
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot.withSource(pkg)
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the card is already up → nothing to do.
        if (sig == lastSignature && showing) return
        lastSignature = sig
        // Scrolling through history changes what is on screen but brings nothing
        // new: keep the card exactly as it is (no clearing, no re-analysis). The
        // same goes for the user's own new message. Only a first look at a chat or
        // a new incoming message goes on to analysis.
        when (gate.observe(snapshot.messages)) {
            NewMessageGate.Verdict.NOTHING_NEW, NewMessageGate.Verdict.NEW_FROM_ME -> {
                overlay?.showIdle(snapshot.title); return
            }
            NewMessageGate.Verdict.FIRST_LOOK, NewMessageGate.Verdict.NEW_FROM_OTHER -> Unit
        }
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            overlay?.showIdle(snapshot.title); return
        }

        // A newer message makes any round in flight stale; the card keeps showing
        // the previous result until the new one lands.
        cancelInFlight()
        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    /**
     * A chat app's tree can briefly read as "not a chat" while its list re-lays
     * out (QQ while scrolling). Leave the conversation only if it still reads
     * that way after a short grace period; any good read in between cancels it.
     */
    private val confirmLeave = Runnable {
        val root = rootInActiveWindow
        val adapter = root?.packageName?.toString()?.let { adapters[it] }
        val gone = root == null || adapter == null ||
            adapter.extract(root, resources) == null || targetFor(root) == null
        if (gone) {
            leaveConversation()
            markReadable(false, currentUnreadableReason())
            overlay?.showIdle(null)
        }
    }

    private fun scheduleLeave() {
        if (session.target == null) {
            leaveConversation()
            markReadable(false, currentUnreadableReason())
            overlay?.showIdle(null)
            return
        }
        main.removeCallbacks(confirmLeave)
        main.postDelayed(confirmLeave, LEAVE_CONFIRM_MS)
    }

    /**
     * 读不到「这个 App 的聊天页」时别第一帧就判死。推特是 Compose 画的，
     * 键盘收起、转场动画、刚点进私信的那一两帧里输入框可能还不在树里，
     * 一帧就 leaveConversation 会让面板塌成球、而且再也点不动。
     * 这里退让两次（300ms / 600ms）再判定离开。
     */
    private fun retryWhenUnreadable() {
        if (unreadableAttempts < 2) {
            unreadableAttempts++
            main.removeCallbacks(unreadableRetry)
            main.postDelayed(unreadableRetry, if (unreadableAttempts == 1) 300L else 600L)
            return
        }
        unreadableAttempts = 0
        // 判定为认不出：球变灰并在点击时说明原因
        markReadable(false, currentUnreadableReason())
        scheduleLeave()
    }

    /** 认不出时的一句话原因（[explainUnreadable] 的精简版，给球用）。 */
    private fun currentUnreadableReason(): String {
        val root = rootInActiveWindow
        val pkgs = root?.packageName?.toString()
        return when {
            root == null -> "读不到当前窗口，请重新打开聊天界面"
            isBlocked(pkgs) -> "这个应用被主动屏蔽了"
            pkgs == packageName -> "你现在在 App 自己的界面里"
            pkgs !in adapters -> "「${appName(pkgs)}」还没适配，长按球选「截屏识别一次」试试"
            else -> "这一屏不像聊天窗口，进到具体对话里再点"
        }
    }

    /** 白名单挡掉时说明原因（节流），别让面板静悄悄消失。 */
    private fun noWhitelistNotice() {
        if (whitelistNoticeShown) return
        whitelistNoticeShown = true
        overlay?.toast("当前会话不在白名单里，去设置里把标题加进白名单才能用")
        main.postDelayed({ whitelistNoticeShown = false }, 5000)
    }

    /**
     * 只剩一颗球之后的显隐逻辑。
     *
     * 以前是两颗：蓝球（分析卡片入口）+ 绿球（一键直填），直填模式两颗同时在屏上，
     * 这就是用户看到的「一蓝一绿」。现在统一成一颗，绿球那条路彻底关掉。
     * 球只在「助手开着 + 第三方应用」上摆出来：桌面、系统界面、自己的设置页
     * 交给 [confirmAwayHide] 的宽限期收走，屏蔽应用交给 [showBlocked]，
     * 这里不抢着摆，免得先闪一下再消失。
     */
    private fun syncBubble(pkg: String?) {
        if (!prefs.enabled) { overlay?.hide(); return }
        val drop = pkg == null || isBlocked(pkg) || pkg == packageName ||
            pkg == "com.android.systemui" ||
            pkg.contains("launcher", ignoreCase = true) || pkg == "com.miui.home"
        if (drop) return
        if (overlay?.isShowing() != true) overlay?.showIdle(null)
    }

    /** 球上的状态：认得出就是蓝色，认不出是灰色 ?，点球会说原因。 */
    private fun markReadable(ok: Boolean, reason: String? = null) {
        val ov = overlay ?: return
        ov.setReadState(
            if (ok) OverlayController.ReadState.READY
            else OverlayController.ReadState.no(reason ?: "当前屏幕不是聊天界面，或读不到消息内容")
        )
    }

    /**
     * 一次性说明「为什么现在识别不了」。点灰球时调用。
     * 原因按优先级逐条判断，只回一句最贴切的话。
     */
    private fun explainUnreadable() {
        val root = rootInActiveWindow
        val pkgs = root?.packageName?.toString()
        val why = when {
            !prefs.enabled -> "助手已关闭，去设置里重新打开"
            !prefs.hasKey() -> "还没填判断接口的密钥，去设置里填"
            root == null -> "读不到当前窗口，请重新打开聊天界面"
            isBlocked(pkgs) -> "这个应用被主动屏蔽了（微信、Soul 不在支持范围）"
            pkgs == packageName || pkgs == "com.android.systemui" ||
                pkgs?.contains("launcher", true) == true ->
                "你现在在桌面或系统界面，请先进到聊天里"
            pkgs !in adapters -> "「${appName(pkgs)}」还没适配，长按球选「截屏识别一次」"
            else -> {
                val adapter = adapters[pkgs]
                val snap = adapter?.extract(root!!, resources)
                when {
                    snap == null -> "这一屏不像聊天窗口（可能是列表页、设置页），请进到具体对话里"
                    !prefs.isAllowed(snap.title) -> "这个会话不在白名单里，去设置里加上它的标题"
                    snap.messages.isEmpty() -> "在聊天里，但读不到消息文字（可能要开 OCR 兜底）"
                    else -> "刚刚没读到，稍微动一下屏幕再试"
                }
            }
        }
        overlay?.toast(why)
    }

    /** 包名 → 用户认得的名字，说不认识时才不像报错。 */
    private fun appName(pkg: String?): String = when (pkg) {
        "com.tencent.mobileqq" -> "QQ"
        "com.ss.android.lark" -> "飞书"
        "com.twitter.android" -> "推特"
        "com.whatsapp" -> "WhatsApp"
        "com.tencent.mm" -> "微信"
        null -> "这个应用"
        else -> pkg
    }

    /**
     * 就地重抓一次当前窗口，成功就把它当作当前会话。
     * 这是「点了没反应」的解药：以前只有 maybeCapture 有机会刷新 currentSnapshot，
     * 一旦它被清空，手动点击就永远短路。
     */
    private fun captureNow(): ChatSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (isBlocked(pkg)) return null
        val adapter = adapters[pkg] ?: return null
        val snapshot = adapter.extract(root, resources) ?: return null
        val target = targetFor(root) ?: return null
        observeTarget(target)
        overlay?.setApp(pkg)
        currentSnapshot = snapshot.withSource(pkg)
        activePkg = pkg
        if (!snapshot.title.isNullOrBlank()) lastKnownTitle = snapshot.title
        return snapshot
    }

    /** 手动入口：先抓，抓不到给人话；抓到就按当前模式走。 */
    private fun analyzeOnDemand() {
        val snapshot = currentSnapshot ?: captureNow()
        if (snapshot == null) {
            // 认不出的时候不要只丢一句「没认出」——把具体原因说清楚
            markReadable(false)
            explainUnreadable()
            return
        }
        markReadable(true)
        if (!prefs.isAllowed(snapshot.title)) {
            noWhitelistNotice()
            return
        }
        pendingSnapshot = snapshot
        runAnalysis()
    }

    /**
     * 直填球被点：已经有一轮候选就换下一条，否则开始新一轮（分析 → 排第一 → 直接填）。
     * 三条点完回到第 1 条，不重新生成（按定稿）。
     */
    private fun quickTap() {
        if (!prefs.enabled) { overlay?.toast("助手已关闭"); return }
        if (analyzing || quickAnalysisPending) { overlay?.toast("正在分析，稍等一下"); return }
        if (!prefs.hasKey()) { overlay?.toast("还没填判断接口的密钥，去设置里填"); return }
        val snapshot = captureNow()
        if (snapshot == null) {
            // 认不出：球标灰，再把原因说清楚（和卡片模式点灰球同一套话）
            markReadable(false, currentUnreadableReason())
            explainUnreadable()
            return
        }
        if (!prefs.isAllowed(snapshot.title)) {
            markReadable(false, "这个会话不在白名单里，去设置里加上它的标题")
            noWhitelistNotice()
            return
        }
        markReadable(true)
        val sig = QuickFillSession.signatureOf(snapshot)
        if (QuickFillSession.matches(sig)) {
            val next = QuickFillSession.next()
            if (next != null) {
                val (text, i, total) = next
                overlay?.setBubbleLabel(label(i, total))
                fillInput(session.token() ?: return, text, "已填入第 $i/$total 条，发送还是你点")
                return
            }
        }
        // 新一轮：分析完再填，期间别让用户连点烧 token
        QuickFillSession.clear()
        pendingSnapshot = snapshot
        quickAnalysisPending = true
        // 分析中：球保持当前状态
        runAnalysis(quick = sig)
    }

    /** 球上的极简状态：`①/③`。 */
    private fun label(i: Int, total: Int): String = "$i/$total"

    /** 直填模式分析完成：把候选存成这一轮，直接把第 1 条填进去。 */
    private fun quickFillStart(
        token: ConversationSession.Token,
        ranked: List<com.aron.chatbot.core.RankedReply>,
        error: String?,
        sig: String
    ) {
        quickAnalysisPending = false
        if (error != null || ranked.isEmpty()) {
            // 没烧出候选：进度字样清掉，球回到认得出的蓝色
            overlay?.setReadState(OverlayController.ReadState.READY)
            overlay?.toast(error ?: "没生成出候选回复")
            return
        }
        QuickFillSession.reset(sig, ranked.map { it.text })
        val next = QuickFillSession.next() ?: return
        val (text, i, total) = next
        overlay?.setBubbleLabel(label(i, total))
        fillInput(token, text, "已填入第 $i/$total 条，发送还是你点")
    }

    /** "换一组" / 重试: redraft the candidates only; the judgment on the card stays. */
    private fun regenerateReplies() {
        if (analyzing || regenerating || destroyed || !prefs.enabled) return
        val snapshot = lastAnalyzed ?: currentSnapshot ?: return
        val token = session.token() ?: return
        if (!isCurrent(token)) return
        if (!prefs.hasKey()) { overlay?.showError("未设置判断接口密钥，去设置里填"); return }
        regenerating = true
        val avoid = overlay?.currentReplyTexts().orEmpty()
        overlay?.beginRegenerate()
        val client = JevClient(prefs)
        val rel = prefs.relationship
        val ctx = lastCtx
        val count = prefs.candidateCount
        submitAnalysis {
            var replyError: String? = null
            val ranked = try {
                withHardTimeout(REPLY_TIMEOUT_MS, "回复接口") {
                    client.draftAndRank(snapshot, rel, ctx, count, avoid)
                }
            } catch (e: Exception) {
                replyError = replyErrorText(e)
                Log.w(TAG, "redraft failed: $replyError")
                emptyList()
            }
            main.post {
                regenerating = false
                if (isCurrent(token)) overlay?.showReplies(ranked, replyError) { text -> fillInput(token, text) }
            }
        }
    }

    private fun replyErrorText(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return if (m.contains("超时")) "生成超时" else m
    }

    private fun isBlocked(pkg: String?): Boolean = pkg != null && pkg in BLOCKED_PKGS

    /**
     * Foreground is a blocked app: no node-tree read, no screenshot, no OCR, no
     * fill. We only surface a one-time "not supported" notice. [auto] events
     * (accessibility callbacks) show it once per visit — [blockedNoticeShown]
     * dedupes them; a manual bubble tap ([auto] = false) always shows it. Never
     * gated by the user's whitelist (it is an info notice, not a read).
     */
    private fun showBlocked(auto: Boolean) {
        leaveConversation()
        if (auto && blockedNoticeShown) return
        blockedNoticeShown = true
        // No popup panel there — take the overlay off entirely and show the
        // reason once as a small transient toast.
        overlay?.hide()
        overlay?.toast(BLOCKED_MSG)
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    /**
     * [quick] 非空 = 「一键直填」那一套发起的分析：不碰卡片、不弹候选，
     * 排名回来直接填第 1 条（见 [quickFillStart]）。null = 原来的卡片流程。
     */
    private fun runAnalysis(quick: String? = null) {
        val snapshot = pendingSnapshot ?: return
        if (analyzing || destroyed || !prefs.enabled) {
            // A lost round used to strand analyzing=true here forever, with
            // nothing in logcat saying why updates stopped — never again.
            if (analyzing) Log.w(TAG, "round skipped: previous round still in flight")
            quickAnalysisPending = false
            return
        }
        val previous = session.token() ?: run { quickAnalysisPending = false; return }
        if (!isCurrent(previous)) { quickAnalysisPending = false; return }
        if (!prefs.hasKey()) {
            quickAnalysisPending = false
            if (quick == null) overlay?.showError("未设置判断接口密钥，去设置里填")
            else overlay?.toast("还没填判断接口的密钥，去设置里填")
            return
        }
        val token = session.begin() ?: run { quickAnalysisPending = false; return }
        analyzing = true
        if (quick == null) {
            overlay?.beginRound()
            overlay?.setNote(snapshot.note)
        }
        val count = prefs.candidateCount
        val client = JevClient(prefs)
        val rel = prefs.relationship
        submitAnalysis {
            val ctx = try {
                ContextBuilder.build(this, snapshot, token.target.pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post {
                if (!isCurrent(token)) return@post
                lastAnalyzed = snapshot
                lastCtx = ctx
                if (quick == null) overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
                var remaining = 2
                fun completed() {
                    remaining--
                    if (remaining == 0) {
                        analyzing = false
                        analysisTasks.clear()
                    }
                }
                submitAnalysis {
                    val judgment = try {
                        withHardTimeout(JUDGE_TIMEOUT_MS, "判断接口") { client.judge(snapshot, rel, ctx) }
                    } catch (e: Exception) {
                        Log.w(TAG, "judge round failed: ${e.message}")
                        null
                    }
                    main.post {
                        if (isCurrent(token)) {
                            if (quick != null) {
                                // 直填模式下不弹卡片：判断出问题就一句话说清楚，球继续留着
                                when {
                                    judgment == null -> overlay?.toast("判断接口超时，再点一次重试")
                                    judgment.error != null -> overlay?.toast(judgment.error)
                                }
                            } else {
                                when {
                                    judgment == null -> overlay?.showError("判断接口超时，请重试")
                                    judgment.error != null -> overlay?.showError(judgment.error)
                                    else -> overlay?.showJudgment(judgment)
                                }
                            }
                        }
                        // Outside the guard: a stale session must never strand
                        // analyzing=true — that bricked every later round.
                        completed()
                    }
                }
                submitAnalysis {
                    var replyError: String? = null
                    val t0 = SystemClock.elapsedRealtime()
                    val ranked = try {
                        withHardTimeout(REPLY_TIMEOUT_MS, "回复接口") {
                            client.draftAndRank(snapshot, rel, ctx, count)
                        }
                    } catch (e: Exception) {
                        replyError = replyErrorText(e)
                        Log.w(TAG, "reply failed after ${SystemClock.elapsedRealtime() - t0}ms: $replyError")
                        emptyList()
                    }
                    if (replyError == null) {
                        Log.i(TAG, "reply ok after ${SystemClock.elapsedRealtime() - t0}ms ranked=${ranked.size}")
                    }
                    main.post {
                        if (isCurrent(token)) {
                            if (quick != null) {
                                // 直填：不弹候选，直接填第 1 条
                                quickFillStart(token, ranked, replyError, quick)
                            } else {
                                overlay?.showReplies(ranked, replyError) { text -> fillInput(token, text) }
                            }
                        } else if (quick != null) {
                            quickAnalysisPending = false
                        }
                        completed() // outside the guard: see the judge round
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        // Blocked apps: a manual "截屏识别一次" must NOT take a screenshot there —
        // just show the notice (a manual tap always shows it).
        if (isBlocked(pkg)) { showBlocked(auto = false); return }
        if (pkg in NO_SCREENSHOT_PKGS) { overlay?.showError("这个应用里不使用截屏识别"); return }
        // Top bar text, if this app has one we can read; else the first OCR line.
        val title = root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        val target = root?.let { targetFor(it) } ?: run {
            overlay?.toast("无法确认当前会话，请等待标题加载后重试")
            return
        }
        observeTarget(target)
        ocrCapture(title, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (ocrBusy || destroyed || !prefs.enabled) return
        val token = session.token() ?: return
        if (!isCurrent(token)) return
        ocrBusy = true
        screenCapture.capture(shouldCapture = { isCurrent(token) }) { res ->
            if (!isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) res.bitmap.recycle()
                ocrBusy = false
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this
                    // screen is done — the next event may retry, still held
                    // back by ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not
                    // something the user can act on — nagging would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg, token)
                    } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual, token)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String, token: ConversationSession.Token) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false, token = token)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual, token)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        ocrBusy = false
        if (!isCurrent(token)) return
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) {
            leaveConversation()
            markReadable(false, "这个会话不在白名单里，去设置里加上它的标题")
            overlay?.showIdle(null)
            return
        }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot.withSource(pkg)
        overlay?.setApp(pkg.ifEmpty { null })
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        lastSignature = sig
        // Same rule as the tree path: only a first look or a new incoming message
        // starts an automatic round; scrolling keeps the card as it is.
        val verdict = gate.observe(snapshot.messages)
        val fresh = verdict == NewMessageGate.Verdict.FIRST_LOOK || verdict == NewMessageGate.Verdict.NEW_FROM_OTHER
        val auto = fresh && prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
        if (manual || auto) {
            cancelInFlight()
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis()
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Resolve only the originating chat's input, never an arbitrary foreground editor. */
    private fun inputFor(token: ConversationSession.Token): AccessibilityNodeInfo? {
        if (!isCurrent(token)) return null
        val root = rootInActiveWindow ?: return null
        if (targetFor(root) != token.target) return null
        val input = when (token.target.pkg) {
            "com.tencent.mobileqq" -> root.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/input").firstOrNull()
            "com.ss.android.lark" -> root.findAccessibilityNodeInfosByViewId("com.ss.android.lark:id/kb_rich_text_content").firstOrNull()
            "com.twitter.android" -> findEditable(root)
            else -> null // Unknown apps support explicit clipboard copy, not unverified writes.
        }
        // Re-read the node after SET_TEXT: the accessibility cache may still
        // contain the previous draft even though the write already succeeded.
        input ?: return null
        if (!input.refresh()) return null
        return input.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
    }

    /**
     * Fill without blocking the main thread; all retries re-resolve the original target.
     *
     * [okMsg] 是成功后的提示。直填模式会带上「第 2/3 条」这种信息，卡片模式用默认那句。
     * 注意 ACTION_SET_TEXT 本身就是整段替换：先清空上一条再写新的，是同一个动作，
     * 不需要额外一次清空（多写一次反而容易把输入框弄没收）。
     */
    private fun fillInput(
        token: ConversationSession.Token,
        text: String,
        okMsg: String = "已填入，确认后自己发送"
    ) {
        fun finish(ok: Boolean) {
            if (!isCurrent(token)) return
            if (ok) overlay?.toast(okMsg)
            else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
        }
        if (!isCurrent(token)) { overlay?.toast("会话已变化，请重新分析后填入"); return }
        if (inputFor(token) == null) { finish(false); return }
        GuardedInputWriter(
            resolve = {
                inputFor(token)?.let { node ->
                    object : GuardedInputWriter.Input {
                        override val text: String? get() = node.text?.toString()
                        override fun setText(text: String) = setTextRaw(node, text)
                        override fun focus() { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        override fun paste() { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                    }
                }
            },
            later = { delay, action -> main.postDelayed({ action() }, delay) },
            copy = { copyToClipboard(it) },
            complete = { finish(it) }
        ).fill(text)
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword) {
                if (found != null) return null // Ambiguous editor: clipboard only.
                found = node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return if (stack.isEmpty()) found else null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("chatbot_reply", text))
    }

    override fun onInterrupt() {
        leaveConversation()
        overlay?.hide()
    }

    override fun onDestroy() {
        destroyed = true
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        leaveConversation()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.hide()
        overlay = null
        QuickFillSession.clear()
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "CHATBOT"

        /** 推特包名：「一键直填」只对这一个 App 生效。 */
        private const val X_PKG = "com.twitter.android"

        /** Hard ceiling on one round's judge/reply HTTP. HttpURLConnection bounds
         *  connect/read per attempt but NOT DNS resolution, so a wedged lookup
         *  could hold a round's callback forever (panel stuck on 生成中, every
         *  later round silently dropped). Deliberately shorter than HttpJson's
         *  own worst retry tail (~165s). 30s each: past that the user would rather
         *  see "生成超时" with a retry than keep waiting. */
        private const val JUDGE_TIMEOUT_MS = 30_000L
        private const val REPLY_TIMEOUT_MS = 30_000L

        /** Grace period before a "not a chat" read really leaves the conversation. */
        private const val LEAVE_CONFIRM_MS = 1200L

        /** Apps that are never read: they protect their content against screen
         *  reading or screenshots, so any capture attempt there is off-limits.
         *  No adapter, no capture, only a one-time "not supported" notice. */
        /** Supported apps that are read through the node tree only and never
         *  screenshotted: no OCR fallback, no manual "截屏识别一次". */
        private val NO_SCREENSHOT_PKGS = setOf(WhatsAppRules.PACKAGE)

        private val BLOCKED_PKGS = setOf("com.tencent.mm", "cn.soulapp.android")

        /** Shown once per visit to a blocked app. Plain words, full-width
         *  punctuation; steers the user to a supported app. */
        private const val BLOCKED_MSG =
            "这个应用暂不支持，请在别的软件上使用"

        /** How long a transient non-chat foreground must persist before the
         *  bubble is taken away (#18). */
        private const val FOREGROUND_CONFIRM_MS = 1500L

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
