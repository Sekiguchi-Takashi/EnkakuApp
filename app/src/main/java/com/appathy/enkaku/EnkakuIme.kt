package com.appathy.enkaku

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class EnkakuIme : InputMethodService(), FlickKeyboardView.Listener, SlideKeyboardView.Listener {

    private enum class Layout { SLIDE, FLICK }

    private fun prefs() = getSharedPreferences("enkaku", MODE_PRIVATE)
    private fun savedLayout(): Layout =
        if (prefs().getString("layout", "SLIDE") == "FLICK") Layout.FLICK else Layout.SLIDE

    private fun mode(): String = prefs().getString("mode", Converter.MODE_PREDICT) ?: Converter.MODE_PREDICT
    private fun grade(): Int = prefs().getInt("grade", 1)

    private fun setMode(m: String) {
        prefs().edit().putString("mode", m).apply()
        buffer.setLength(0)
        slideView?.setTextMode(m == Converter.MODE_TEXT)
        refreshCandidates()
    }

    private var slideView: SlideKeyboardView? = null
    private var dialog: Dialog? = null

    // 直前に打ち込んだ連続文字列（変換・予測の読み。空白/改行/確定でリセット）
    private val buffer = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        // v1.5: マルチモニタの登録内容を一度だけ初期化する
        if (!prefs().getBoolean("reset_v15", false)) {
            Predictor.clear(this)
            prefs().edit().putBoolean("reset_v15", true).apply()
        }
    }

    private fun buildView(layout: Layout): View = when (layout) {
        Layout.SLIDE -> SlideKeyboardView(this).also {
            it.listener = this
            slideView = it
            it.setTextMode(mode() == Converter.MODE_TEXT)
        }
        Layout.FLICK -> FlickKeyboardView(this).also { it.listener = this; slideView = null }
    }

    override fun onCreateInputView(): View = buildView(savedLayout())

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        buffer.setLength(0)
        slideView?.setTextMode(mode() == Converter.MODE_TEXT)
        refreshCandidates()
    }

    // ユーザーがカーソルを動かす等でbufferと実テキストがずれたら読みを破棄する
    // （ずれたままにすると候補確定時のdeleteSurroundingTextが無関係な文字を消す）
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (buffer.isEmpty()) return
        val before = currentInputConnection?.getTextBeforeCursor(buffer.length, 0)
        if (before == null || !before.toString().endsWith(buffer.toString())) {
            buffer.setLength(0)
            refreshCandidates()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        dismissDialog()
    }

    private fun refreshCandidates() {
        val v = slideView ?: return
        v.setCandidates(buildCandidates(v.isJapanese()))
    }

    // モード別のマルチモニタ内容
    private fun buildCandidates(japanese: Boolean): List<String> {
        val m = mode()
        if (m == Converter.MODE_TEXT) return emptyList()

        val reading = buffer.toString()
        if (reading.isEmpty()) return emptyList()

        if (m == Converter.MODE_PREDICT || !japanese) {
            return Predictor.suggest(this, reading, 5)
        }

        // 漢字 / 小学校モード: 5文字までは候補、6文字以降は改行まで出さない
        if (reading.length > Converter.KANJI_MAX_LEN) return emptyList()
        val out = ArrayList<String>()
        for (w in Predictor.suggest(this, reading, 2)) if (!out.contains(w)) out.add(w)
        for (w in Converter.candidates(this, reading, m, grade(), 8)) {
            if (!out.contains(w)) out.add(w)
            if (out.size >= 5) break
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

    // ---- メニュー ----

    private fun dismissDialog() {
        try { dialog?.dismiss() } catch (e: Throwable) { }
        dialog = null
    }

    private fun showIme(d: Dialog): Boolean {
        val token = slideView?.windowToken ?: return false
        val w = d.window ?: return false
        val lp = w.attributes
        lp.token = token
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        w.attributes = lp
        w.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dismissDialog()
        dialog = d
        d.show()
        return true
    }

    private fun panel(): LinearLayout {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setBackgroundColor(Color.parseColor("#1E2A38"))
        val p = (12 * resources.displayMetrics.density).toInt()
        ll.setPadding(p, p, p, p)
        return ll
    }

    private fun heading(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.parseColor("#ECEFF1"))
        tv.textSize = 16f
        tv.setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        return tv
    }

    private fun menuButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onMenu() {
        val ll = panel()
        ll.addView(heading("マルチモニタ設定"))
        ll.addView(menuButton("Gboardへ切り替える") { dismissDialog(); switchToGboard() })

        val cur = mode()
        val schoolLabel = "小学校モード（" + grade() + "年生）" + if (cur == Converter.MODE_SCHOOL) " ●" else ""
        ll.addView(menuButton(schoolLabel) { dismissDialog(); showGradeMenu() })
        ll.addView(menuButton("漢字モード" + if (cur == Converter.MODE_KANJI) " ●" else "") {
            dismissDialog(); setMode(Converter.MODE_KANJI)
        })
        ll.addView(menuButton("予測モード（既定）" + if (cur == Converter.MODE_PREDICT) " ●" else "") {
            dismissDialog(); setMode(Converter.MODE_PREDICT)
        })
        ll.addView(menuButton("テキストモード" + if (cur == Converter.MODE_TEXT) " ●" else "") {
            dismissDialog(); setMode(Converter.MODE_TEXT)
        })
        val mouseOn = LearnService.instance?.isMouseOn() == true
        ll.addView(menuButton("マウスモード" + if (mouseOn) " ●" else "") {
            dismissDialog(); startMouseMode()
        })
        ll.addView(menuButton("閉じる") { dismissDialog() })

        val d = Dialog(this)
        d.setContentView(wrapScroll(ll))
        if (!showIme(d)) {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        }
    }

    private fun startMouseMode() {
        val svc = LearnService.instance
        if (svc == null) {
            Toast.makeText(this, "先に「常駐学習（ユーザー補助）」をONにしてください", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "「他のアプリの上に表示」を許可してください", Toast.LENGTH_LONG).show()
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        requestHideSelf(0)
        svc.toggleMouse()
    }

    private fun wrapScroll(v: View): View {
        val sv = ScrollView(this)
        sv.addView(v)
        return sv
    }

    private fun showGradeMenu() {
        val ll = panel()
        ll.addView(heading("小学校モード: 学年を選ぶ"))
        for (g in 1..6) {
            ll.addView(menuButton(g.toString() + "年生" + if (grade() == g && mode() == Converter.MODE_SCHOOL) " ●" else "") {
                prefs().edit().putInt("grade", g).apply()
                dismissDialog()
                setMode(Converter.MODE_SCHOOL)
            })
        }
        ll.addView(menuButton("閉じる") { dismissDialog() })
        val d = Dialog(this)
        d.setContentView(wrapScroll(ll))
        showIme(d)
    }

    // ---- テキストモードのポップアップ ----

    override fun onTextSlot(button: Int) {
        val ll = panel()
        ll.addView(heading("テキスト " + button + "（各30文字まで）"))

        for (slot in 1..TextSlots.SLOTS) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            val text = TextSlots.get(this, button, slot)
            val bText = Button(this)
            bText.isAllCaps = false
            bText.text = if (text.isEmpty()) slot.toString() + ". （未登録）" else slot.toString() + ". " + text
            bText.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            bText.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            bText.setOnClickListener {
                if (text.isNotEmpty()) {
                    currentInputConnection?.commitText(text, 1)
                    dismissDialog()
                } else {
                    dismissDialog(); openEditor(button, slot)
                }
            }
            row.addView(bText)

            val bEdit = Button(this)
            bEdit.isAllCaps = false
            bEdit.text = if (text.isEmpty()) "登録" else "変更"
            bEdit.setOnClickListener { dismissDialog(); openEditor(button, slot) }
            row.addView(bEdit)

            val bDel = Button(this)
            bDel.isAllCaps = false
            bDel.text = "削除"
            bDel.setOnClickListener {
                TextSlots.delete(this, button, slot)
                dismissDialog()
                onTextSlot(button)
            }
            row.addView(bDel)

            ll.addView(row)
        }
        ll.addView(menuButton("閉じる") { dismissDialog() })

        val d = Dialog(this)
        d.setContentView(wrapScroll(ll))
        if (!showIme(d)) openEditor(button, 1)
    }

    // 登録・変更はアプリ側の編集画面で行う（IME内では文字入力ができないため）
    private fun openEditor(button: Int, slot: Int) {
        val i = Intent(this, TextSlotActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra("button", button)
        i.putExtra("slot", slot)
        startActivity(i)
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
    override fun onTransformLast() = transform(true)

    // 長押しは逆順に巡回
    override fun onTransformLastBack() = transform(false)

    private fun transform(forward: Boolean) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return
        val ch = before.toString()
        val wasKatakana = ch[0].code in 0x30A1..0x30F6
        val hira = if (wasKatakana) kataToHira(ch) else ch
        val cycled = (if (forward) KanaTables.cycleChar(hira) else KanaTables.cycleCharBack(hira)) ?: return
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

    private fun switchToGboard() {
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
