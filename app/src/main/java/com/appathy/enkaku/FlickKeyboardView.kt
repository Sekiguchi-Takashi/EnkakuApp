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
import kotlin.math.abs

class FlickKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onCommit(text: String)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
        fun onCursor(left: Boolean)
        fun onTransformLast()
        fun onNextIme()
        fun onHide()
    }

    var listener: Listener? = null

    enum class Mode { KANA_H, KANA_K, ABC, NUM }
    private var mode = Mode.KANA_H

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // 色
    private val colBg = Color.parseColor("#1E2A38")
    private val colKey = Color.parseColor("#2C3E50")
    private val colFunc = Color.parseColor("#22303C")
    private val colKeyDown = Color.parseColor("#3A6EA5")
    private val colText = Color.parseColor("#ECEFF1")
    private val colHint = Color.parseColor("#6B8299")
    private val colPopup = Color.parseColor("#0F1620")
    private val colSel = Color.parseColor("#5B9BD5")

    private val pKey = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colText; textAlign = Paint.Align.CENTER
    }
    private val pHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colHint; textAlign = Paint.Align.CENTER
    }

    // レイアウト: 5列 x 4行。col0=左機能, col1..3=中央, col4=右機能
    private val cols = 5
    private val rows = 4
    private var kw = 0f
    private var kh = 0f
    private val gap = dp(3f)

    // キー種別
    private sealed class Key
    private class Char12(val slot: Int) : Key()          // 中央12キー(slot 0..11)
    private object ModeKey : Key()
    private object NextIme : Key()
    private object CurLeft : Key()
    private object CurRight : Key()
    private object Del : Key()
    private object Space : Key()
    private object Enter : Key()
    private object Hide : Key()

    // 各セルのKey定義（列優先で配置）
    private fun keyAt(col: Int, row: Int): Key {
        if (col == 0) return when (row) {
            0 -> ModeKey; 1 -> NextIme; 2 -> CurLeft; else -> CurRight
        }
        if (col == 4) return when (row) {
            0 -> Del; 1 -> Space; 2 -> Enter; else -> Hide
        }
        val slot = row * 3 + (col - 1)   // 0..11
        return Char12(slot)
    }

    // slot -> フリック配列（中央,左,上,右,下）。KANAは濁点キーをslot9に持つ。
    private fun dirsFor(slot: Int): Array<String>? {
        return when (mode) {
            Mode.KANA_H, Mode.KANA_K -> when (slot) {
                in 0..8 -> KanaTables.flick[slot]
                9 -> null                       // 濁点/小書き特殊キー
                10 -> KanaTables.flick[9]        // わ
                11 -> KanaTables.flick[10]       // 、
                else -> null
            }
            Mode.ABC -> latinDirs(slot)
            Mode.NUM -> arrayOf(KanaTables.symbols[slot], "", "", "", "")
        }
    }

    private fun latinDirs(slot: Int): Array<String> {
        val g = KanaTables.latin[slot]
        val a = arrayOf("", "", "", "", "")
        if (g.length == 1 && g[0] == ' ') { a[0] = "␣"; return a }
        for (i in g.indices) {
            when (i) { 0 -> a[0] = g[0].toString(); 1 -> a[1] = g[1].toString()
                2 -> a[2] = g[2].toString(); 3 -> a[3] = g[3].toString() }
        }
        return a
    }

    private fun isDakutenSlot(slot: Int) =
        (mode == Mode.KANA_H || mode == Mode.KANA_K) && slot == 9

    // タッチ状態
    private var downCol = -1
    private var downRow = -1
    private var downX = 0f
    private var downY = 0f
    private var curDir = 0            // 0中央 1左 2上 3右 4下
    private var flicking = false

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var repeatFired = false

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = dp(210f).toInt()      // コンパクトな固定高さ
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        kw = (w - gap * (cols + 1)) / cols
        kh = (h - gap * (rows + 1)) / rows
    }

    private fun cellRect(col: Int, row: Int): RectF {
        val l = gap + col * (kw + gap)
        val t = gap + row * (kh + gap)
        return RectF(l, t, l + kw, t + kh)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(colBg)
        pText.textSize = kh * 0.42f
        pHint.textSize = kh * 0.24f
        for (col in 0 until cols) for (row in 0 until rows) {
            val k = keyAt(col, row)
            val r = cellRect(col, row)
            val down = (col == downCol && row == downRow)
            drawKey(c, k, r, down)
        }
        if (flicking && downCol in 1..3) drawFlickPopup(c)
    }

    private fun roundKey(c: Canvas, r: RectF, color: Int) {
        pKey.color = color
        c.drawRoundRect(r, dp(8f), dp(8f), pKey)
    }

    private fun centerText(c: Canvas, s: String, r: RectF, paint: Paint) {
        val fm = paint.fontMetrics
        val y = r.centerY() - (fm.ascent + fm.descent) / 2
        c.drawText(s, r.centerX(), y, paint)
    }

    private fun drawKey(c: Canvas, k: Key, r: RectF, down: Boolean) {
        when (k) {
            is Char12 -> {
                if (isDakutenSlot(k.slot)) {
                    roundKey(c, r, if (down) colKeyDown else colFunc)
                    centerText(c, "゛小", r, pText)
                    return
                }
                val dirs = dirsFor(k.slot)
                roundKey(c, r, if (down && !flicking) colKeyDown else colKey)
                if (dirs != null) {
                    centerText(c, dirs[0], r, pText)
                    // 周囲の小さなヒント
                    if (dirs[1].isNotEmpty()) drawHint(c, dirs[1], r.left + kw * 0.16f, r.centerY())
                    if (dirs[2].isNotEmpty()) drawHint(c, dirs[2], r.centerX(), r.top + kh * 0.20f)
                    if (dirs[3].isNotEmpty()) drawHint(c, dirs[3], r.right - kw * 0.16f, r.centerY())
                    if (dirs[4].isNotEmpty()) drawHint(c, dirs[4], r.centerX(), r.bottom - kh * 0.16f)
                }
            }
            ModeKey -> { roundKey(c, r, if (down) colKeyDown else colFunc); centerText(c, modeLabel(), r, pText) }
            NextIme -> { roundKey(c, r, if (down) colKeyDown else colFunc); centerText(c, "🌐", r, pText) }
            CurLeft -> { roundKey(c, r, if (down) colKeyDown else colFunc); centerText(c, "◀", r, pText) }
            CurRight -> { roundKey(c, r, if (down) colKeyDown else colFunc); centerText(c, "▶", r, pText) }
            Del -> { roundKey(c, r, if (down) colKeyDown else colFunc); centerText(c, "⌫", r, pText) }
            Space -> { roundKey(c, r, if (down) colKeyDown else colFunc); centerText(c, "空白", r, pText) }
            Enter -> { roundKey(c, r, if (down) colKeyDown else colFunc); centerText(c, "改行", r, pText) }
            Hide -> { roundKey(c, r, if (down) colKeyDown else colFunc); centerText(c, "▽", r, pText) }
        }
    }

    private fun drawHint(c: Canvas, s: String, x: Float, y: Float) {
        val fm = pHint.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, pHint)
    }

    private fun modeLabel() = when (mode) {
        Mode.KANA_H -> "あ"; Mode.KANA_K -> "ア"; Mode.ABC -> "A"; Mode.NUM -> "1"
    }

    private fun drawFlickPopup(c: Canvas) {
        val slot = (keyAt(downCol, downRow) as? Char12)?.slot ?: return
        val dirs = dirsFor(slot) ?: return
        val base = cellRect(downCol, downRow)
        val cx = base.centerX(); val cy = base.centerY()
        val s = kw * 0.92f
        fun cell(dir: Int): RectF = when (dir) {
            1 -> RectF(cx - s * 1.5f, cy - s / 2, cx - s * 0.5f, cy + s / 2)
            2 -> RectF(cx - s / 2, cy - s * 1.5f, cx + s / 2, cy - s * 0.5f)
            3 -> RectF(cx + s * 0.5f, cy - s / 2, cx + s * 1.5f, cy + s / 2)
            4 -> RectF(cx - s / 2, cy + s * 0.5f, cx + s / 2, cy + s * 1.5f)
            else -> RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        }
        pText.textSize = s * 0.5f
        for (d in 0..4) {
            if (dirs[d].isEmpty()) continue
            val rr = cell(d)
            pKey.color = if (d == curDir) colSel else colPopup
            c.drawRoundRect(rr, dp(8f), dp(8f), pKey)
            centerText(c, if (mode == Mode.KANA_K) KanaTables.toKatakana(dirs[d]) else dirs[d], rr, pText)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                downCol = ((e.x - gap) / (kw + gap)).toInt().coerceIn(0, cols - 1)
                downRow = ((e.y - gap) / (kh + gap)).toInt().coerceIn(0, rows - 1)
                repeatFired = false
                curDir = 0
                flicking = downCol in 1..3 && keyAt(downCol, downRow) is Char12 &&
                        !isDakutenSlot((keyAt(downCol, downRow) as Char12).slot)
                startRepeatIfNeeded()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (flicking) {
                    val dx = e.x - downX; val dy = e.y - downY
                    val th = kw * 0.30f
                    curDir = if (abs(dx) < th && abs(dy) < th) 0
                    else if (abs(dx) > abs(dy)) { if (dx < 0) 1 else 3 }
                    else { if (dy < 0) 2 else 4 }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                if (e.actionMasked == MotionEvent.ACTION_UP) commitTouch()
                downCol = -1; downRow = -1; flicking = false
                invalidate()
            }
        }
        return true
    }

    private fun startRepeatIfNeeded() {
        val k = keyAt(downCol, downRow)
        if (k is Del) {
            repeatRunnable = object : Runnable {
                override fun run() {
                    repeatFired = true
                    listener?.onBackspace()
                    repeatHandler.postDelayed(this, 60)
                }
            }
            repeatHandler.postDelayed(repeatRunnable!!, 400)
        }
    }

    private fun stopRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun commitTouch() {
        when (val k = keyAt(downCol, downRow)) {
            is Char12 -> {
                if (isDakutenSlot(k.slot)) { listener?.onTransformLast(); return }
                val dirs = dirsFor(k.slot) ?: return
                val raw = dirs[curDir]
                if (raw.isEmpty()) return
                if (mode == Mode.NUM || mode == Mode.ABC) { listener?.onCommit(raw); return }
                val out = if (mode == Mode.KANA_K) KanaTables.toKatakana(raw) else raw
                listener?.onCommit(out)
            }
            ModeKey -> {
                mode = when (mode) {
                    Mode.KANA_H -> Mode.KANA_K; Mode.KANA_K -> Mode.ABC
                    Mode.ABC -> Mode.NUM; Mode.NUM -> Mode.KANA_H
                }
                invalidate()
            }
            NextIme -> listener?.onNextIme()
            CurLeft -> listener?.onCursor(true)
            CurRight -> listener?.onCursor(false)
            Del -> if (!repeatFired) listener?.onBackspace()
            Space -> listener?.onSpace()
            Enter -> listener?.onEnter()
            Hide -> listener?.onHide()
        }
    }
}
