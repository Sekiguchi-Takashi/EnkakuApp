package com.appathy.enkaku

import android.content.Context

// テキストモードの定型文（ボタン1〜7 × スロット1〜10、各30文字まで）
object TextSlots {

    const val BUTTONS = 7
    const val SLOTS = 10
    const val MAX_LEN = 30

    private fun key(button: Int, slot: Int) = "tm_" + button + "_" + slot

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("enkaku_text", Context.MODE_PRIVATE)

    fun get(ctx: Context, button: Int, slot: Int): String =
        prefs(ctx).getString(key(button, slot), "") ?: ""

    fun set(ctx: Context, button: Int, slot: Int, text: String) {
        val t = if (text.length > MAX_LEN) text.substring(0, MAX_LEN) else text
        prefs(ctx).edit().putString(key(button, slot), t).apply()
    }

    fun delete(ctx: Context, button: Int, slot: Int) {
        prefs(ctx).edit().remove(key(button, slot)).apply()
    }

    fun list(ctx: Context, button: Int): List<String> {
        val out = ArrayList<String>()
        for (i in 1..SLOTS) out.add(get(ctx, button, i))
        return out
    }

    fun firstEmpty(ctx: Context, button: Int): Int {
        for (i in 1..SLOTS) if (get(ctx, button, i).isEmpty()) return i
        return 0
    }
}
