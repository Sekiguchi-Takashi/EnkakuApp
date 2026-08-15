package com.appathy.enkaku

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

class EnkakuIme : InputMethodService(), FlickKeyboardView.Listener, SlideKeyboardView.Listener {

    private enum class Layout { SLIDE, FLICK }

    private fun prefs() = getSharedPreferences("enkaku", MODE_PRIVATE)
    private fun savedLayout(): Layout =
        if (prefs().getString("layout", "SLIDE") == "FLICK") Layout.FLICK else Layout.SLIDE

    private var slideView: SlideKeyboardView? = null

    // 直前に打ち込んだ連続文字列（予測の手がかり。空白/改行/候補確定でリセット）
    private val buffer = StringBuilder()

    private fun buildView(layout: Layout): View = when (layout) {
        Layout.SLIDE -> SlideKeyboardView(this).also { it.listener = this; slideView = it }
        Layout.FLICK -> FlickKeyboardView(this).also { it.listener = this; slideView = null }
    }

    override fun onCreateInputView(): View = buildView(savedLayout())

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        buffer.setLength(0)
        refreshCandidates()
    }

    private fun refreshCandidates() {
        val v = slideView ?: return
        v.setCandidates(buildCandidates(v.isJapanese(), v.isConvertOn()))
    }

    // 予測（学習済み）を先に、足りない分を内蔵辞書の変換候補で埋める
    private fun buildCandidates(japanese: Boolean, convertOn: Boolean): List<String> {
        val reading = buffer.toString()
        val useConv = japanese && convertOn && reading.isNotEmpty()
        val out = ArrayList<String>()
        for (w in Predictor.suggest(this, reading, if (useConv) 2 else 5)) {
            if (!out.contains(w)) out.add(w)
        }
        if (useConv) {
            for (w in Converter.convert(this, reading, 8)) {
                if (!out.contains(w)) out.add(w)
                if (out.size >= 5) break
            }
        }
        return if (out.size > 5) out.subList(0, 5) else out
    }

    private fun learnBuffer() {
        if (buffer.length >= 2) {
            val b = buffer.toString()
            Predictor.learn(this, b, b)
        }
        buffer.setLength(0)
    }

    override fun onCandidate(text: String) {
        val ic = currentInputConnection ?: return
        val reading = buffer.toString()
        if (reading.isNotEmpty()) ic.deleteSurroundingText(reading.length, 0)
        ic.commitText(text, 1)
        if (reading.isNotEmpty()) Predictor.learn(this, reading, text)
        buffer.setLength(0)
        refreshCandidates()
    }

    override fun onModeChanged(japanese: Boolean) {
        buffer.setLength(0)
        refreshCandidates()
    }

    override fun onConvertToggled(on: Boolean) {
        refreshCandidates()
    }

    // Gboard へ直接切り替える（見つからなければIME選択画面）
    override fun onGboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val target = imm.enabledInputMethodList?.firstOrNull {
            it.packageName == "com.google.android.inputmethod.latin"
        }
        val id = target?.id
        if (id == null) {
            imm.showInputMethodPicker()
            return
        }
        var ok = false
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                switchInputMethod(id)
                ok = true
            } else {
                val token = window?.window?.attributes?.token
                if (token != null) {
                    @Suppress("DEPRECATION")
                    imm.setInputMethod(token, id)
                    ok = true
                }
            }
        } catch (e: Throwable) {
            ok = false
        }
        if (!ok) imm.showInputMethodPicker()
    }

    override fun onSwitchLayout() {
        val next = if (savedLayout() == Layout.SLIDE) Layout.FLICK else Layout.SLIDE
        prefs().edit().putString("layout", next.name).apply()
        setInputView(buildView(next))
    }

    // 横持ちでも全画面入力にしない（画面を専有しないため）
    override fun onEvaluateFullscreenMode(): Boolean = false

    // ---- Listener ----

    override fun onCommit(text: String) {
        currentInputConnection?.commitText(text, 1)
        buffer.append(text)
        refreshCandidates()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel != null && sel.isNotEmpty()) {
            ic.commitText("", 1)
            buffer.setLength(0)
        } else {
            ic.deleteSurroundingText(1, 0)
            if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
        }
        refreshCandidates()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_UNSPECIFIED
        val noEnter = (ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION
        if (noEnter == 0 && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        learnBuffer()
        refreshCandidates()
    }

    override fun onSpace() {
        currentInputConnection?.commitText(" ", 1)
        learnBuffer()
        refreshCandidates()
    }

    override fun onCursor(left: Boolean) {
        val code = if (left) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // 直前1文字を濁点/半濁点/小書きへ巡回変換
    override fun onTransformLast() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return
        val ch = before.toString()
        // カタカナはひらがなへ寄せてから変換し、元がカタカナなら戻す
        val wasKatakana = ch[0].code in 0x30A1..0x30F6
        val hira = if (wasKatakana) kataToHira(ch) else ch
        val cycled = KanaTables.cycleChar(hira) ?: return
        val out = if (wasKatakana) KanaTables.toKatakana(cycled) else cycled
        ic.deleteSurroundingText(1, 0)
        ic.commitText(out, 1)
        if (buffer.isNotEmpty()) {
            buffer.setLength(buffer.length - 1)
            buffer.append(out)
        }
        refreshCandidates()
    }

    private fun kataToHira(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val code = c.code
            if (code in 0x30A1..0x30F6) sb.append((code - 0x60).toChar()) else sb.append(c)
        }
        return sb.toString()
    }

    override fun onNextIme() {
        val ok = if (android.os.Build.VERSION.SDK_INT >= 28) switchToNextInputMethod(false) else false
        if (!ok) {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        }
    }

    override fun onHide() {
        requestHideSelf(0)
    }
}
