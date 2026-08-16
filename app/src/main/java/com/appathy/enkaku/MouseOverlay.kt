package com.appathy.enkaku

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

// タッチで相対的にポインタを動かすマウスモードのオーバーレイ。
// ポインタとタッチ位置は独立していて、指を動かした「差分」だけポインタが動く
// （指を画面下に置いても、ポインタは中央付近から相対移動する）。
// 実際のクリック/スクロール/戻るは LearnService（AccessibilityService）が実行する。
class MouseOverlay(private val ctx: Context) {

    interface Actions {
        fun click(x: Float, y: Float)
        fun doubleClick(x: Float, y: Float)
        fun scroll(x: Float, y: Float, up: Boolean)
        fun back()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: OverlayView? = null
    var actions: Actions? = null

    fun isShowing(): Boolean = root != null

    fun toggle(): Boolean {
        if (isShowing()) { hide(); return false }
        show(); return true
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val v = OverlayView(ctx)
        root = v
        wm.addView(v, lp)
    }

    fun hide() {
        root?.let { try { wm.removeView(it) } catch (e: Throwable) {} }
        root = null
    }

    private inner class OverlayView(c: Context) : View(c) {
        private val dm = resources.displayMetrics
        private fun dp(v: Float) = v * dm.density

        private var px = dm.widthPixels / 2f
        private var py = dm.heightPixels / 2f

        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private var downOnBar = false

        private val barH = dp(46f)
        private val gap = dp(6f)

        // ボタン: クリック / ダブル / スクロール↑ / スクロール↓ / 戻る / 閉じる
        private val labels = listOf("クリック", "ダブル", "▲", "▼", "戻る", "×")
        private var pressed = -1

        private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.WHITE
        }
        private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(13f)
        }

        private fun barTop() = height - barH

        private fun buttonRect(i: Int): RectF {
            val n = labels.size
            val w = (width - gap * (n + 1)) / n
            val l = gap + i * (w + gap)
            val t = barTop() + gap / 2f
            return RectF(l, t, l + w, height - gap / 2f)
        }

        private fun buttonAt(x: Float, y: Float): Int {
            if (y < barTop()) return -1
            for (i in labels.indices) if (buttonRect(i).contains(x, y)) return i
            return -1
        }

        override fun onDraw(c: Canvas) {
            // ポインタ
            pFill.color = Color.parseColor("#CC3A6EA5")
            c.drawCircle(px, py, dp(11f), pFill)
            c.drawCircle(px, py, dp(11f), pStroke)
            pFill.color = Color.WHITE
            c.drawCircle(px, py, dp(2.5f), pFill)

            // 下部ボタンバー
            pFill.color = Color.parseColor("#E6161E27")
            c.drawRect(0f, barTop(), width.toFloat(), height.toFloat(), pFill)
            for (i in labels.indices) {
                val r = buttonRect(i)
                pFill.color = if (i == pressed) Color.parseColor("#3A6EA5")
                    else Color.parseColor("#2C3E50")
                c.drawRoundRect(r, dp(8f), dp(8f), pFill)
                val fm = pText.fontMetrics
                c.drawText(labels[i], r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, pText)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val b = buttonAt(e.x, e.y)
                    if (b >= 0) {
                        downOnBar = true; pressed = b; invalidate()
                    } else {
                        downOnBar = false
                        dragging = true
                        lastX = e.x; lastY = e.y
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        // タッチの移動差分だけポインタを動かす（相対操作）
                        px = (px + (e.x - lastX)).coerceIn(0f, width.toFloat())
                        py = (py + (e.y - lastY)).coerceIn(0f, barTop() - dp(2f))
                        lastX = e.x; lastY = e.y
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (downOnBar) {
                        val b = buttonAt(e.x, e.y)
                        if (b == pressed && b >= 0) fire(b)
                        pressed = -1; downOnBar = false; invalidate()
                    }
                    dragging = false
                }
            }
            return true
        }

        private fun fire(i: Int) {
            when (i) {
                0 -> actions?.click(px, py)
                1 -> actions?.doubleClick(px, py)
                2 -> actions?.scroll(px, py, true)
                3 -> actions?.scroll(px, py, false)
                4 -> actions?.back()
                5 -> hide()
            }
        }
    }
}
