package com.appathy.enkaku

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

// 常駐学習サービス。
// どのキーボード（Gboard含む）で入力しても、画面上のテキスト変化から
// 「かな読み → 確定文字列」を拾って本アプリのPredictorに学習させる。
// パスワード欄は必ず除外し、学習データは端末内（SharedPreferences）にのみ保存する。
class LearnService : AccessibilityService(), MouseOverlay.Actions {

    companion object {
        // 実行中のサービス参照（IMEのメニューからマウスモードを起動するため）
        var instance: LearnService? = null
    }

    private var mouse: MouseOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        mouse?.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- マウスモード ----

    fun toggleMouse(): Boolean {
        val m = mouse ?: MouseOverlay(this).also { it.actions = this; mouse = it }
        return m.toggle()
    }

    fun isMouseOn(): Boolean = mouse?.isShowing() == true

    private fun tapPath(x: Float, y: Float): Path {
        val p = Path(); p.moveTo(x, y); return p
    }

    override fun click(x: Float, y: Float) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 0, 60))
            .build()
        dispatchGesture(g, null, null)
    }

    override fun doubleClick(x: Float, y: Float) {
        val first = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 0, 50)).build()
        dispatchGesture(first, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) {
                val second = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 40, 50)).build()
                dispatchGesture(second, null, null)
            }
        }, null)
    }

    override fun scroll(x: Float, y: Float, up: Boolean) {
        val dm = resources.displayMetrics
        val dist = dm.heightPixels * 0.35f
        val path = Path()
        if (up) {
            // 上スクロール = 内容を上へ = 指を下から上へ
            path.moveTo(x, y); path.lineTo(x, (y - dist).coerceAtLeast(0f))
        } else {
            path.moveTo(x, y); path.lineTo(x, (y + dist).coerceAtMost(dm.heightPixels.toFloat()))
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250)).build()
        dispatchGesture(g, null, null)
    }

    override fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }


    // 直近のテキスト（画面上の入力欄ごとに1つ。欄の判別はwindowId+viewIdで代用）
    private val lastText = HashMap<Long, String>()
    private val MAX_TRACK = 20
    private val MAX_TEXT = 4000

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (event.isPassword) return
        if (packageName == event.packageName) {
            // 自アプリ（試し打ち欄・登録画面）はIME側で学習済みのため除外
            return
        }

        val now = event.text?.joinToString("") ?: return
        if (now.length > MAX_TEXT) return

        val key = (event.windowId.toLong() shl 32) or
            ((event.source?.hashCode() ?: 0).toLong() and 0xFFFFFFFFL)
        val prev = lastText[key] ?: ""
        if (lastText.size > MAX_TRACK) lastText.clear()
        lastText[key] = now

        if (prev == now || prev.isEmpty()) return

        // 前回との差分（共通の前置き・後置きを除いた置換部分）を取る
        var p = 0
        val minLen = minOf(prev.length, now.length)
        while (p < minLen && prev[p] == now[p]) p++
        var s = 0
        while (s < minLen - p && prev[prev.length - 1 - s] == now[now.length - 1 - s]) s++
        val removed = prev.substring(p, prev.length - s)
        val inserted = now.substring(p, now.length - s)

        // かな → 別の文字列 に置き換わった = 変換確定。読みと単語のペアで学習
        if (removed.length in 1..24 && isKana(removed) &&
            inserted.isNotEmpty() && inserted != removed && !isKana(inserted)) {
            Predictor.learn(this, toHiragana(removed), inserted.take(TextSlots.MAX_LEN))
            return
        }

        // 区切り文字が打たれた = 直前の語が完成。かな/英数の語をそのまま学習
        if (inserted.length == 1 && isDelimiter(inserted[0])) {
            val body = now.substring(0, p)
            val word = lastToken(body)
            if (word.length in 2..24) Predictor.learn(this, toHiragana(word), word)
        }
    }

    private fun isDelimiter(c: Char) =
        c == ' ' || c == '\n' || c == '、' || c == '。' || c == ',' || c == '.' ||
        c == '!' || c == '?' || c == '！' || c == '？' || c == '　'

    private fun isKana(s: String): Boolean {
        for (c in s) {
            val code = c.code
            val hira = code in 0x3041..0x309F
            val kata = code in 0x30A0..0x30FF
            if (!hira && !kata) return false
        }
        return s.isNotEmpty()
    }

    private fun toHiragana(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val code = c.code
            if (code in 0x30A1..0x30F6) sb.append((code - 0x60).toChar()) else sb.append(c)
        }
        return sb.toString()
    }

    // 末尾の連続したかな or 英数字のかたまりを取り出す
    private fun lastToken(s: String): String {
        var i = s.length
        while (i > 0) {
            val c = s[i - 1]
            val code = c.code
            val kana = code in 0x3041..0x30FF
            val ascii = c.isLetterOrDigit() && code < 0x80
            if (!kana && !ascii) break
            i--
        }
        val t = s.substring(i)
        // かなと英数の混在は語として怪しいので捨てる
        return if (isKana(t) || t.all { it.code < 0x80 }) t else ""
    }

    override fun onInterrupt() {
    }
}
