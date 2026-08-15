package com.appathy.enkaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

class SlideKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onCommit(text: String)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
        fun onCursor(left: Boolean)
        fun onTransformLast()
        fun onNextIme()
        fun onHide()
        fun onSwitchLayout()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val colBg = Color.parseColor("#1E2A38")
    private val colHead = Color.parseColor("#2C3E50")
    private val colFunc = Color.parseColor("#22303C")
    private val colDown = Color.parseColor("#3A6EA5")
    private val colText = Color.parseColor("#ECEFF1")
    private val colStrip = Color.parseColor("#0F1620")
    private val colSel = Color.parseColor("#5B9BD5")
    private val colKata = Color.parseColor("#9AB8D6")
    private val colMark = Color.parseColor("#C9A15B")

    private val pKey = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colText; textAlign = Paint.Align.CENTER
    }

    // ベース行のキー
    private val FIXED = 0
    private val HEAD0 = 1          // 1..10 が あかさたなはまやらわ
    private val NUM = 11
    private val DAKU = 12
    private val SYM = 13
    private val SPACE = 14
    private val ENTER = 15
    private val HIDE = 16
    private val keyCount = 17

    private var kw = 0f
    private var kh = 0f
    private val gap = dp(3f)

    // 状態
    private var lock = false
    private enum class Strip { NONE, GYO, NUM, SYM }
    private var strip = Strip.NONE
    private var stripGyo = 0
    private var persist = false        // 固定ON時に展開を保持
    private var selIdx = 0
    private var selecting = false      // 保持ストリップをタップ選択中

    private var downKey = -1
    private var downX = 0f
    private var moved = false

    private val handler = Handler(Looper.getMainLooper())
    private var longRunnable: Runnable? = null
    private var longFired = false

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(72f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        kw = (w - gap * (keyCount + 1)) / keyCount
        kh = h - gap * 2
    }

    private fun keyRect(i: Int): RectF {
        val l = gap + i * (kw + gap)
        return RectF(l, gap, l + kw, gap + kh)
    }

    private fun currentStripList(): List<String> = when (strip) {
        Strip.GYO -> KanaTables.expandStrip(stripGyo)
        Strip.NUM -> KanaTables.digitStrip
        Strip.SYM -> KanaTables.symbolStrip
        Strip.NONE -> emptyList()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(colBg)
        pText.textSize = kh * 0.44f
        if (strip == Strip.NONE) drawBaseRow(c) else drawStrip(c)
    }

    private fun centerText(c: Canvas, s: String, r: RectF, color: Int) {
        pText.color = color
        val fm = pText.fontMetrics
        c.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, pText)
    }

    private fun roundKey(c: Canvas, r: RectF, color: Int) {
        pKey.color = color; c.drawRoundRect(r, dp(7f), dp(7f), pKey)
    }

    private fun drawBaseRow(c: Canvas) {
        for (i in 0 until keyCount) {
            val r = keyRect(i)
            val down = (i == downKey && !longFired)
            when (i) {
                FIXED -> {
                    roundKey(c, r, if (lock) colDown else colFunc)
                    centerText(c, if (lock) "固定●" else "固定", r, colText)
                }
                in HEAD0..(HEAD0 + 9) -> {
                    roundKey(c, r, if (down) colDown else colHead)
                    centerText(c, KanaTables.gyoHeads[i - HEAD0], r, colText)
                }
                NUM -> { roundKey(c, r, if (down) colDown else colFunc); centerText(c, "数", r, colText) }
                DAKU -> { roundKey(c, r, if (down) colDown else colFunc); centerText(c, "゛小", r, colText) }
                SYM -> { roundKey(c, r, if (down) colDown else colFunc); centerText(c, "記号", r, colText) }
                SPACE -> { roundKey(c, r, if (down) colDown else colFunc); centerText(c, "空白", r, colText) }
                ENTER -> { roundKey(c, r, if (down) colDown else colFunc); centerText(c, "改行", r, colText) }
                HIDE -> { roundKey(c, r, if (down) colDown else colFunc); centerText(c, "▽", r, colText) }
            }
        }
    }

    private fun drawStrip(c: Canvas) {
        val list = currentStripList()
        if (list.isEmpty()) return
        val n = list.size
        val cellW = (width - gap * (n + 1)) / n
        for (i in 0 until n) {
            val l = gap + i * (cellW + gap)
            val r = RectF(l, gap, l + cellW, gap + kh)
            val sel = (i == selIdx)
            roundKey(c, r, if (sel) colSel else colStrip)
            val label = list[i]
            val color = when {
                strip == Strip.NUM && (label == "0" || label == "5") -> colMark
                strip == Strip.GYO && isKatakana(label) -> colKata
                else -> colText
            }
            centerText(c, label, r, color)
        }
    }

    private fun isKatakana(s: String) = s.isNotEmpty() && s[0].code in 0x30A1..0x30FA

    private fun keyAt(x: Float): Int {
        val i = ((x - gap) / (kw + gap)).toInt()
        return i.coerceIn(0, keyCount - 1)
    }

    private fun stripIndexAt(x: Float): Int {
        val n = currentStripList().size
        if (n == 0) return 0
        val cellW = (width - gap * (n + 1)) / n
        val i = ((x - gap) / (cellW + gap)).toInt()
        return i.coerceIn(0, n - 1)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e.x)
            MotionEvent.ACTION_MOVE -> onMove(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                onUp(e.actionMasked == MotionEvent.ACTION_UP)
        }
        return true
    }

    private fun onDown(x: Float) {
        downX = x; moved = false; longFired = false
        cancelLong()

        // 保持ストリップが開いている → タップ選択
        if (strip != Strip.NONE && persist) {
            // 左端(固定位置)タップで取り消し
            if (keyAt(x) == FIXED) { closeStrip(); invalidate(); return }
            selecting = true
            selIdx = stripIndexAt(x)
            invalidate()
            return
        }

        downKey = keyAt(x)
        when (downKey) {
            in HEAD0..(HEAD0 + 9) -> {
                strip = Strip.GYO; stripGyo = downKey - HEAD0
                persist = lock
                selIdx = centerIndex()
                invalidate()
            }
            NUM -> { strip = Strip.NUM; persist = lock; selIdx = 0; invalidate() }
            SYM -> { strip = Strip.SYM; persist = lock; selIdx = 0; invalidate() }
            FIXED, HIDE -> scheduleLong(downKey)
        }
        if (strip == Strip.NONE) invalidate()
    }

    private fun centerIndex(): Int {
        // GYO ストリップの中央（行頭ひらがな）位置
        val row = KanaTables.gyo[stripGyo]
        return row.size  // 左カタカナ n 個の直後
    }

    private fun onMove(x: Float) {
        if (kotlin.math.abs(x - downX) > dp(6f)) moved = true
        if (moved) cancelLong()
        if (selecting || (strip != Strip.NONE && !persist)) {
            selIdx = stripIndexAt(x)
            invalidate()
        }
    }

    private fun onUp(committed: Boolean) {
        cancelLong()
        if (longFired) { downKey = -1; invalidate(); return }

        // 保持ストリップのタップ選択確定
        if (selecting) {
            if (committed) commitStrip()
            selecting = false; closeStrip(); invalidate(); return
        }

        // スライド確定（非固定）
        if (strip != Strip.NONE && !persist) {
            if (committed) commitStrip()
            closeStrip(); invalidate(); return
        }

        // 固定ONで今開いたばかり → 保持したまま何もしない
        if (strip != Strip.NONE && persist) { downKey = -1; invalidate(); return }

        // 機能キー
        if (committed) when (downKey) {
            FIXED -> lock = !lock
            DAKU -> listener?.onTransformLast()
            SPACE -> listener?.onSpace()
            ENTER -> listener?.onEnter()
            HIDE -> listener?.onHide()
        }
        downKey = -1
        invalidate()
    }

    private fun commitStrip() {
        val list = currentStripList()
        if (selIdx in list.indices) {
            val s = list[selIdx]
            if (s.isNotEmpty()) listener?.onCommit(s)
        }
    }

    private fun closeStrip() {
        strip = Strip.NONE; persist = false; selecting = false; downKey = -1
    }

    private fun scheduleLong(key: Int) {
        longRunnable = Runnable {
            longFired = true
            when (key) {
                FIXED -> listener?.onSwitchLayout()
                HIDE -> listener?.onNextIme()
            }
        }
        handler.postDelayed(longRunnable!!, 500)
    }

    private fun cancelLong() {
        longRunnable?.let { handler.removeCallbacks(it) }
        longRunnable = null
    }
}
