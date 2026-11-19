package com.aron.chatbot.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.aron.chatbot.core.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「一键直填」的触发球：一个独立的小圆球，点了就分析 + 直接填入，再点换下一条。
 *
 * **已停用（v1.1）**：屏幕只留一颗球，直填入口并进了 [OverlayController] 的球
 * （点球 = 直填，长按 = 菜单）。这个类不再被服务实例化，留在这里只为不删文件；
 * 别再接回去，否则「一蓝一绿」会复活。
 *
 * 球上只显示极短的状态（`①/③`），啰嗦的话走 toast，别挡聊天内容。
 */
class QuickFillBubble(
    private val ctx: Context,
    private val prefs: Prefs,
    private val onTap: () -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var ball: TextView? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels
    private val ballSize get() = dp(54)

    fun isShowing(): Boolean = root != null

    /** 显示球。已经显示就什么都不做（幂等，服务里会被反复调用）。 */
    fun show(): Boolean {
        if (root != null) return true
        if (!Settings.canDrawOverlays(ctx)) return false
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.quickBubbleX.takeIf { it >= 0 } ?: dp(10)
            y = prefs.quickBubbleY.takeIf { it >= 0 } ?: dp(260)
        }
        val wrapper = FrameLayout(ctx)
        val view = TextView(ctx).apply {
            text = "Jev"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 22, 163, 74))
            }
            layoutParams = FrameLayout.LayoutParams(ballSize, ballSize)
        }
        attachDrag(view, params)
        wrapper.addView(view)
        return try {
            wm.addView(wrapper, params)
            root = wrapper
            lp = params
            ball = view
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "quick bubble addView failed: " + e.message)
            root = null; lp = null; ball = null
            false
        }
    }

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null; lp = null; ball = null
    }

    /** 球上的一行状态，例如 `①/③`。传 null 回到默认的 "CB"。 */
    fun setLabel(text: String?) {
        ball?.text = text ?: "CB"
    }

    private fun attachDrag(v: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) moved = true
                    if (moved) {
                        // 侧边留出余量：小米/HyperOS 的极边缘是返回手势热区
                        params.x = (startX + dx).coerceIn(dp(4), maxOf(dp(4), screenW - ballSize - dp(4)))
                        params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                        updateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.quickBubbleX = params.x
                        prefs.quickBubbleY = params.y
                    } else {
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun updateLayout() {
        val r = root ?: return
        val p = lp ?: return
        runCatching { wm.updateViewLayout(r, p) }
    }

    companion object {
        private const val TAG = "CHATBOT"
    }
}