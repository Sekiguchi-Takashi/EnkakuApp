package com.appathy.enkaku

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

class EnkakuIme : InputMethodService(), FlickKeyboardView.Listener {

    private var kb: FlickKeyboardView? = null

    override fun onCreateInputView(): View {
        val v = FlickKeyboardView(this)
        v.listener = this
        kb = v
        return v
    }

    // 横持ちでも全画面入力にしない（画面を専有しないため）
    override fun onEvaluateFullscreenMode(): Boolean = false

    // ---- Listener ----

    override fun onCommit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel != null && sel.isNotEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
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
    }

    override fun onSpace() {
        currentInputConnection?.commitText(" ", 1)
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
