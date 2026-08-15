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

    // キーID
    private val K_HEAD0 = 0            // 0..9 が あかさたなはまやらわ
    private val K_FIX = 10
    private val K_NUM = 11
    private val K_SYM = 12
    private val K_DAKU = 13
    private val K_SPACE = 14
    private val K_ENTER = 15
    private val K_DEL = 16
    private val K_HIDE = 17

    // 3行レイアウト（1行目=あかさたな / 2行目=はまやらわ / 3行目=機能）
    private val rows: List<List<Int>> = listOf(
        listOf(0, 1, 2, 3, 4),
        listOf(5, 6, 7, 8, 9),
        listOf(K_FIX, K_NUM, K_SYM, K_DAKU, K_SPACE, K_ENTER, K_DEL, K_HIDE)
    )

    private val gap = dp(3f)
    private val rowH = dp(40f)
    private val maxKeyW = dp(46f)
    private val rowW = FloatArray(3)
    private val rowLeft = FloatArray(3)
    private val rowKeyW = FloatArray(3)

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
    private var repeatRunnable: Runnable? = null
    private var longFired = false

    private fun totalHeight() = gap * 4 + rowH * 3
    private fun rowTop(r: Int) = gap + r * (rowH + gap)
    private fun funcRowTop() = rowTop(2)
    private fun stripRegionTop() = rowTop(0)
    private fun stripRegionBottom() = rowTop(1) + rowH

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), totalHeight().toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        for (r in rows.indices) {
            val n = rows[r].size
            val fit = (w - gap * (n + 1)) / n
            val kw = if (fit < maxKeyW) fit else maxKeyW
            rowKeyW[r] = kw
            rowW[r] = kw * n + gap * (n - 1)
            rowLeft[r] = (w - rowW[r]) / 2f
        }
    }

    private fun keyRect(r: Int, c: Int): RectF {
        val l = rowLeft[r] + c * (rowKeyW[r] + gap)
        val t = rowTop(r)
        return RectF(l, t, l + rowKeyW[r], t + rowH)
    }

    private fun currentStripList(): List<String> = when (strip) {
        Strip.GYO -> KanaTables.expandStrip(stripGyo)
        Strip.NUM -> KanaTables.digitStrip
        Strip.SYM -> KanaTables.symbolStrip
        Strip.NONE -> emptyList()
    }

    private fun label(id: Int): String = when (id) {
        in 0..9 -> KanaTables.gyoHeads[id]
        K_FIX -> if (lock) "固定●" else "固定"
        K_NUM -> "数"
        K_SYM -> "記号"
        K_DAKU -> "゛小"
        K_SPACE -> "空白"
        K_ENTER -> "改行"
        K_DEL -> "削除"
        K_HIDE -> "▽"
        else -> ""
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(colBg)
        pText.textSize = rowH * 0.42f
        if (strip == Strip.NONE) {
            for (r in rows.indices) drawRow(c, r)
        } else {
            drawStrip(c)
            drawRow(c, 2)
        }
    }

    private fun drawRow(c: Canvas, r: Int) {
        for (col in rows[r].indices) {
            val id = rows[r][col]
            val rect = keyRect(r, col)
            val down = (id == downKey && !longFired)
            val base = when {
                id == K_FIX && lock -> colDown
                id in 0..9 -> colHead
                else -> colFunc
            }
            roundKey(c, rect, if (down) colDown else base)
            centerText(c, label(id), rect, colText)
        }
    }

    private fun centerText(c: Canvas, s: String, r: RectF, color: Int) {
        pText.color = color
        val fm = pText.fontMetrics
        c.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, pText)
    }

    private fun roundKey(c: Canvas, r: RectF, color: Int) {
        pKey.color = color; c.drawRoundRect(r, dp(7f), dp(7f), pKey)
    }

    private fun drawStrip(c: Canvas) {
        val list = currentStripList()
        if (list.isEmpty()) return
        val n = list.size
        val cellW = (width - gap * (n + 1)) / n
        val h = rowH * 1.4f
        val top = (stripRegionTop() + stripRegionBottom()) / 2f - h / 2f
        for (i in 0 until n) {
            val l = gap + i * (cellW + gap)
            val r = RectF(l, top, l + cellW, top + h)
            val sel = (i == selIdx)
            roundKey(c, r, if (sel) colSel else colStrip)
            val labelText = list[i]
            val color = when {
                strip == Strip.NUM && (labelText == "0" || labelText == "5") -> colMark
                strip == Strip.GYO && isKatakana(labelText) -> colKata
                else -> colText
            }
            centerText(c, labelText, r, color)
        }
    }

    private fun isKatakana(s: String) = s.isNotEmpty() && s[0].code in 0x30A1..0x30FA

    private fun rowAt(y: Float): Int {
        for (r in rows.indices) {
            if (y < rowTop(r) + rowH + gap / 2f) return r
        }
        return rows.size - 1
    }

    private fun keyAt(x: Float, y: Float): Int {
        val r = rowAt(y)
        val n = rows[r].size
        val rel = x - rowLeft[r]
        if (rel < -gap || rel > rowW[r] + gap) return -1
        val i = (rel / (rowKeyW[r] + gap)).toInt().coerceIn(0, n - 1)
        return rows[r][i]
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
            MotionEvent.ACTION_DOWN -> onDown(e.x, e.y)
            MotionEvent.ACTION_MOVE -> onMove(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                onUp(e.actionMasked == MotionEvent.ACTION_UP)
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        downX = x; moved = false; longFired = false
        cancelLong(); cancelRepeat()

        // 保持ストリップが開いている
        if (strip != Strip.NONE && persist) {
            if (y >= funcRowTop()) {
                // 機能行に触れたら展開を閉じてから通常処理
                closeStrip()
            } else {
                selecting = true
                selIdx = stripIndexAt(x)
                invalidate()
                return
            }
        }

        downKey = keyAt(x, y)
        when (downKey) {
            in 0..9 -> {
                strip = Strip.GYO; stripGyo = downKey
                persist = lock
                selIdx = centerIndex()
            }
            K_NUM -> { strip = Strip.NUM; persist = lock; selIdx = 0 }
            K_SYM -> { strip = Strip.SYM; persist = lock; selIdx = 0 }
            K_DEL -> startDelRepeat()
            K_FIX, K_HIDE -> scheduleLong(downKey)
        }
        invalidate()
    }

    private fun centerIndex(): Int {
        // GYO ストリップの中央（行頭ひらがな）位置 = 左カタカナ n 個の直後
        return KanaTables.gyo[stripGyo].size
    }

    private fun onMove(x: Float) {
        if (kotlin.math.abs(x - downX) > dp(6f)) moved = true
        if (moved) { cancelLong() }
        if (selecting || (strip != Strip.NONE && !persist)) {
            selIdx = stripIndexAt(x)
            invalidate()
        }
    }

    private fun onUp(committed: Boolean) {
        cancelLong(); cancelRepeat()
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

        if (committed) when (downKey) {
            K_FIX -> lock = !lock
            K_DAKU -> listener?.onTransformLast()
            K_SPACE -> listener?.onSpace()
            K_ENTER -> listener?.onEnter()
            K_HIDE -> listener?.onHide()
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

    private fun startDelRepeat() {
        listener?.onBackspace()
        val r = object : Runnable {
            override fun run() {
                listener?.onBackspace()
                handler.postDelayed(this, 60)
            }
        }
        repeatRunnable = r
        handler.postDelayed(r, 400)
    }

    private fun cancelRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun scheduleLong(key: Int) {
        val r = Runnable {
            longFired = true
            when (key) {
                K_FIX -> listener?.onSwitchLayout()
                K_HIDE -> listener?.onNextIme()
            }
        }
        longRunnable = r
        handler.postDelayed(r, 500)
    }

    private fun cancelLong() {
        longRunnable?.let { handler.removeCallbacks(it) }
        longRunnable = null
    }
}
