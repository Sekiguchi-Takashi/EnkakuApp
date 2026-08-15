package com.appathy.enkaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

class SlideKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onCommit(text: String)
        fun onCandidate(text: String)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
        fun onCursor(left: Boolean)
        fun onTransformLast()
        fun onTransformLastBack()
        fun onNextIme()
        fun onHide()
        fun onSwitchLayout()
        fun onModeChanged(japanese: Boolean)
        fun onMenu()
        fun onTextSlot(button: Int)
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
    private val colCand = Color.parseColor("#243444")
    private val colCandText = Color.parseColor("#FFD9A0")
    private val colHint = Color.parseColor("#5A6B7C")
    private val colShadow = Color.parseColor("#0B1119")

    private val pKey = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colText; textAlign = Paint.Align.CENTER
    }

    // キーID（0..9=かな行頭 / 20..25=英字グループ）
    private val K_ALPHA0 = 20
    private val K_FIX = 10
    private val K_MODE = 11
    private val K_NUM = 12
    private val K_SYM = 13
    private val K_DAKU = 14
    private val K_ROMAN = 15
    private val K_SPACE = 16
    private val K_ENTER = 17
    private val K_DEL = 18
    private val K_HIDE = 19
    private val K_MENU = 30

    private val ROW_CAND = 0
    private val ROW_MAIN = 1
    private val ROW_FUNC = 2

    private val gap = dp(3f)
    private val rowH = dp(40f)
    private val maxKeyW = dp(46f)
    private val maxCand = 5

    private var japanese = true
    private var textMode = false
    private var candidates: List<String> = emptyList()
    private val sideW = dp(52f)

    private fun mainIds(): List<Int> =
        if (japanese) (0..9).toList()
        else (0 until KanaTables.alphaHeads.size).map { K_ALPHA0 + it }

    private fun funcIds(): List<Int> = listOf(
        K_FIX, K_MODE, K_NUM, K_SYM,
        if (japanese) K_DAKU else K_ROMAN,
        K_SPACE, K_ENTER, K_DEL, K_HIDE
    )

    // 状態
    private var lock = false
    private enum class Strip { NONE, GYO, ALPHA, NUM, SYM, ROMAN }
    private var strip = Strip.NONE
    private var stripIdx = 0            // GYO なら行番号、ALPHA ならグループ番号
    private var persist = false
    private var selIdx = 0
    private var selecting = false

    private var downKey = -1
    private var downCand = -1
    private var downX = 0f
    private var moved = false

    private val handler = Handler(Looper.getMainLooper())
    private var longRunnable: Runnable? = null
    private var repeatRunnable: Runnable? = null
    private var longFired = false

    init {
        val sp = context.getSharedPreferences("enkaku", Context.MODE_PRIVATE)
        japanese = sp.getBoolean("jp_mode", true)
    }

    fun setCandidates(list: List<String>) {
        candidates = if (list.size > maxCand) list.subList(0, maxCand) else list
        invalidate()
    }

    fun isJapanese(): Boolean = japanese

    // マルチモニタをテキストモード（1〜7ボタン）にするか
    fun setTextMode(on: Boolean) {
        textMode = on
        invalidate()
    }

    private fun totalHeight() = gap * 4 + rowH * 3
    private fun rowTop(r: Int) = gap + r * (rowH + gap)
    private fun stripRegionTop() = rowTop(ROW_CAND)
    private fun stripRegionBottom() = rowTop(ROW_MAIN) + rowH

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), totalHeight().toInt())
    }

    private fun keyW(n: Int): Float {
        val fit = (width - gap * (n + 1)) / n
        return if (fit < maxKeyW) fit else maxKeyW
    }

    private fun keyRect(ids: List<Int>, r: Int, c: Int): RectF {
        val n = ids.size
        val kw = keyW(n)
        val total = kw * n + gap * (n - 1)
        val l = (width - total) / 2f + c * (kw + gap)
        val t = rowTop(r)
        return RectF(l, t, l + kw, t + rowH)
    }

    private fun menuRect(): RectF {
        val t = rowTop(ROW_CAND)
        return RectF(gap, t, gap + sideW, t + rowH)
    }

    private fun candLeft(): Float = gap + sideW + gap
    private fun candRight(): Float = width - gap

    private fun candRect(i: Int, n: Int): RectF {
        val area = candRight() - candLeft()
        val cellW = (area - gap * (n - 1)) / n
        val l = candLeft() + i * (cellW + gap)
        val t = rowTop(ROW_CAND)
        return RectF(l, t, l + cellW, t + rowH)
    }

    private fun currentStripList(): List<String> = when (strip) {
        Strip.GYO -> KanaTables.expandStrip(stripIdx)
        Strip.ALPHA -> KanaTables.alphaGroups[stripIdx]
        Strip.NUM -> KanaTables.digitStrip
        Strip.SYM -> if (japanese) KanaTables.symbolStrip else KanaTables.asciiSymbolStrip
        Strip.ROMAN -> KanaTables.romanStrip
        Strip.NONE -> emptyList()
    }

    private fun label(id: Int): String = when (id) {
        in 0..9 -> KanaTables.gyoHeads[id]
        in K_ALPHA0..(K_ALPHA0 + 5) -> KanaTables.alphaHeads[id - K_ALPHA0]
        K_FIX -> if (lock) "固定●" else "固定"
        K_MODE -> if (japanese) "A" else "あ"
        K_NUM -> "数"
        K_SYM -> "記号"
        K_DAKU -> "゛小"
        K_ROMAN -> KanaTables.romanStrip[3]
        K_SPACE -> "空白"
        K_ENTER -> "改行"
        K_DEL -> "削除"
        K_HIDE -> "▽"
        K_MENU -> "⋮"
        else -> ""
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(colBg)
        pText.textSize = rowH * 0.42f
        if (strip == Strip.NONE) {
            drawCandidates(c)
            drawKeyRow(c, mainIds(), ROW_MAIN)
        } else {
            drawStrip(c)
        }
        drawKeyRow(c, funcIds(), ROW_FUNC)
    }

    // マルチモニタ（左端メニュー ＋ 候補 or テキストボタン）
    private fun drawCandidates(c: Canvas) {
        val m = menuRect()
        roundKey(c, m, if (downKey == K_MENU && !longFired) colDown else colFunc)
        centerText(c, label(K_MENU), m, colText)

        if (textMode) {
            for (i in 0 until 7) {
                val r = candRect(i, 7)
                roundKey(c, r, if (i == downCand) colDown else colCand)
                centerText(c, (i + 1).toString(), r, colCandText)
            }
            return
        }

        val n = candidates.size
        if (n == 0) {
            val r = RectF(candLeft(), rowTop(ROW_CAND), candRight(), rowTop(ROW_CAND) + rowH)
            roundKey(c, r, colCand)
            return
        }
        for (i in 0 until n) {
            val r = candRect(i, n)
            roundKey(c, r, if (i == downCand) colDown else colCand)
            centerText(c, fitText(candidates[i], r.width()), r, colCandText)
        }
    }

    private fun fitText(s: String, w: Float): String {
        if (pText.measureText(s) <= w - dp(6f)) return s
        var cut = s
        while (cut.length > 1 && pText.measureText(cut + "…") > w - dp(6f)) {
            cut = cut.substring(0, cut.length - 1)
        }
        return cut + "…"
    }

    private fun drawKeyRow(c: Canvas, ids: List<Int>, r: Int) {
        for (col in ids.indices) {
            val id = ids[col]
            val rect = keyRect(ids, r, col)
            val down = (id == downKey && !longFired)
            val base = when {
                id == K_FIX && lock -> colDown
                id in 0..9 -> colHead
                id in K_ALPHA0..(K_ALPHA0 + 5) -> colHead
                else -> colFunc
            }
            if (r == ROW_MAIN) {
                raisedKey(c, rect, if (down) colDown else base, down)
                val dy = if (down) dp(1.5f) else 0f
                centerText(c, label(id), RectF(rect.left, rect.top + dy, rect.right, rect.bottom + dy), colText)
            } else {
                roundKey(c, rect, if (down) colDown else base)
                centerText(c, label(id), rect, colText)
            }
        }
    }

    private fun shade(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    // 立体キー: 下に影 → 上明るく下暗いグラデーション → 上端ハイライト
    private fun raisedKey(c: Canvas, r: RectF, color: Int, pressed: Boolean) {
        val rad = dp(7f)
        val lift = if (pressed) dp(1.5f) else 0f
        val body = RectF(r.left, r.top + lift, r.right, r.bottom + lift)

        if (!pressed) {
            pKey.shader = null
            pKey.color = colShadow
            c.drawRoundRect(RectF(r.left, r.top + dp(2f), r.right, r.bottom + dp(2.5f)), rad, rad, pKey)
        }

        pKey.shader = LinearGradient(
            body.left, body.top, body.left, body.bottom,
            shade(color, if (pressed) 1.0f else 1.35f), shade(color, if (pressed) 0.85f else 0.9f),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(body, rad, rad, pKey)
        pKey.shader = null

        if (!pressed) {
            pKey.color = shade(color, 1.6f)
            c.drawRoundRect(
                RectF(body.left + dp(3f), body.top + dp(1.5f), body.right - dp(3f), body.top + dp(3.5f)),
                dp(2f), dp(2f), pKey
            )
        }
    }

    private fun centerText(c: Canvas, s: String, r: RectF, color: Int) {
        pText.color = color
        val fm = pText.fontMetrics
        c.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, pText)
    }

    private fun roundKey(c: Canvas, r: RectF, color: Int) {
        pKey.shader = null
        pKey.color = color
        c.drawRoundRect(r, dp(7f), dp(7f), pKey)
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
            val t = list[i]
            val color = when {
                strip == Strip.NUM && (t == "0" || t == "5") -> colMark
                strip == Strip.GYO && isKatakana(t) -> colKata
                else -> colText
            }
            centerText(c, t, r, color)
        }
    }

    private fun isKatakana(s: String) = s.isNotEmpty() && s[0].code in 0x30A1..0x30FA

    private fun rowAt(y: Float): Int {
        for (r in 0..2) {
            if (y < rowTop(r) + rowH + gap / 2f) return r
        }
        return 2
    }

    private fun keyAt(x: Float, y: Float): Int {
        val r = rowAt(y)
        if (r == ROW_CAND) return -1
        val ids = if (r == ROW_MAIN) mainIds() else funcIds()
        val n = ids.size
        val kw = keyW(n)
        val total = kw * n + gap * (n - 1)
        val left = (width - total) / 2f
        val rel = x - left
        if (rel < -gap || rel > total + gap) return -1
        val i = (rel / (kw + gap)).toInt().coerceIn(0, n - 1)
        return ids[i]
    }

    private fun candAt(x: Float): Int {
        val n = if (textMode) 7 else candidates.size
        if (n == 0) return -1
        if (x < candLeft() || x > candRight()) return -1
        val area = candRight() - candLeft()
        val cellW = (area - gap * (n - 1)) / n
        val i = ((x - candLeft()) / (cellW + gap)).toInt()
        return i.coerceIn(0, n - 1)
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
            if (y >= rowTop(ROW_FUNC)) {
                closeStrip()
            } else {
                selecting = true
                selIdx = stripIndexAt(x)
                invalidate()
                return
            }
        }

        // 候補行（G / 候補 / 変換）
        if (rowAt(y) == ROW_CAND) {
            downCand = -1
            downKey = -1
            if (x <= menuRect().right + gap) {
                downKey = K_MENU
            } else {
                downCand = candAt(x)
            }
            invalidate()
            return
        }
        downCand = -1

        downKey = keyAt(x, y)
        when {
            downKey in 0..9 && japanese -> {
                strip = Strip.GYO; stripIdx = downKey
                persist = lock
                selIdx = KanaTables.gyo[stripIdx].size
            }
            downKey in K_ALPHA0..(K_ALPHA0 + 5) -> {
                strip = Strip.ALPHA; stripIdx = downKey - K_ALPHA0
                persist = lock
                selIdx = 0
            }
            downKey == K_NUM -> { strip = Strip.NUM; persist = lock; selIdx = 0 }
            downKey == K_SYM -> { strip = Strip.SYM; persist = lock; selIdx = 0 }
            downKey == K_ROMAN -> { strip = Strip.ROMAN; persist = lock; selIdx = 0 }
            downKey == K_DEL -> startDelRepeat()
            downKey == K_FIX || downKey == K_HIDE || downKey == K_DAKU -> scheduleLong(downKey)
        }
        invalidate()
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
        cancelLong(); cancelRepeat()
        if (longFired) { downKey = -1; downCand = -1; invalidate(); return }

        // マルチモニタの確定
        if (downCand >= 0) {
            if (committed) {
                if (textMode) listener?.onTextSlot(downCand + 1)
                else if (downCand < candidates.size) listener?.onCandidate(candidates[downCand])
            }
            downCand = -1; invalidate(); return
        }

        if (selecting) {
            if (committed) commitStrip()
            selecting = false; closeStrip(); invalidate(); return
        }

        if (strip != Strip.NONE && !persist) {
            if (committed) commitStrip()
            closeStrip(); invalidate(); return
        }

        if (strip != Strip.NONE && persist) { downKey = -1; invalidate(); return }

        if (committed) when (downKey) {
            K_MENU -> listener?.onMenu()
            K_FIX -> lock = !lock
            K_MODE -> toggleMode()
            K_DAKU -> listener?.onTransformLast()
            K_SPACE -> listener?.onSpace()
            K_ENTER -> listener?.onEnter()
            K_HIDE -> listener?.onHide()
        }
        downKey = -1
        invalidate()
    }

    private fun toggleMode() {
        japanese = !japanese
        context.getSharedPreferences("enkaku", Context.MODE_PRIVATE)
            .edit().putBoolean("jp_mode", japanese).apply()
        listener?.onModeChanged(japanese)
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
                K_DAKU -> listener?.onTransformLastBack()
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
