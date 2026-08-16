package com.appathy.enkaku

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

// 常駐学習サービス。
// どのキーボード（Gboard含む）で入力しても、画面上のテキスト変化から
// 「かな読み → 確定文字列」を拾って本アプリのPredictorに学習させる。
// パスワード欄は必ず除外し、学習データは端末内（SharedPreferences）にのみ保存する。
class LearnService : AccessibilityService() {

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
