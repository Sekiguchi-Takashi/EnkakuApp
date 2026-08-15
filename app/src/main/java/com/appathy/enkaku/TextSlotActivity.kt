package com.appathy.enkaku

import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// テキストモードの定型文を登録・変更・削除する画面
class TextSlotActivity : AppCompatActivity() {

    private var button = 1
    private val fields = ArrayList<EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        button = intent.getIntExtra("button", 1).coerceIn(1, TextSlots.BUTTONS)

        val d = resources.displayMetrics.density
        val pad = (14 * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        root.setBackgroundColor(Color.parseColor("#12181F"))

        val title = TextView(this)
        title.text = "テキスト " + button + " の登録（各" + TextSlots.MAX_LEN + "文字まで）"
        title.setTextColor(Color.parseColor("#ECEFF1"))
        title.textSize = 18f
        title.setPadding(0, 0, 0, pad)
        root.addView(title)

        val picker = LinearLayout(this)
        picker.orientation = LinearLayout.HORIZONTAL
        for (b in 1..TextSlots.BUTTONS) {
            val nb = Button(this)
            nb.text = b.toString()
            nb.isAllCaps = false
            nb.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            nb.setOnClickListener {
                save(false)
                button = b
                recreateFor(b)
            }
            picker.addView(nb)
        }
        root.addView(picker)

        for (slot in 1..TextSlots.SLOTS) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val no = TextView(this)
            no.text = slot.toString()
            no.setTextColor(Color.parseColor("#9AB8D6"))
            no.width = (28 * d).toInt()
            row.addView(no)

            val et = EditText(this)
            et.setSingleLine(true)
            et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(TextSlots.MAX_LEN))
            et.setText(TextSlots.get(this, button, slot))
            et.hint = "（未登録）"
            et.setTextColor(Color.parseColor("#ECEFF1"))
            et.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            fields.add(et)
            row.addView(et)

            val del = Button(this)
            del.text = "削除"
            del.isAllCaps = false
            del.setOnClickListener {
                et.setText("")
                TextSlots.delete(this, button, slot)
            }
            row.addView(del)

            root.addView(row)
        }

        val save = Button(this)
        save.text = "保存して閉じる"
        save.isAllCaps = false
        save.setOnClickListener { save(true) }
        root.addView(save)

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
    }

    private fun recreateFor(b: Int) {
        for (slot in 1..TextSlots.SLOTS) {
            fields[slot - 1].setText(TextSlots.get(this, b, slot))
        }
        Toast.makeText(this, "テキスト " + b + " を編集中", Toast.LENGTH_SHORT).show()
    }

    private fun save(finish: Boolean) {
        for (slot in 1..TextSlots.SLOTS) {
            TextSlots.set(this, button, slot, fields[slot - 1].text.toString())
        }
        if (finish) {
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        save(false)
    }
}
