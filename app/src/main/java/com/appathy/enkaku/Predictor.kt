package com.appathy.enkaku

import android.content.Context

// 自前の入力履歴による予測（他IMEの履歴はAndroidの仕様上読めないため本アプリ内で学習する）
// 保存形式: よみ<TAB>単語<TAB>回数
object Predictor {

    private const val PREF = "enkaku_pred"
    private const val KEY = "words"
    private const val MAX_ENTRIES = 500

    private class Entry(val reading: String, val word: String, var count: Int)

    private var cache: ArrayList<Entry>? = null

    private fun load(ctx: Context): ArrayList<Entry> {
        cache?.let { return it }
        val list = ArrayList<Entry>()
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        for (line in raw.split("\n")) {
            if (line.isEmpty()) continue
            val parts = line.split("\t")
            when (parts.size) {
                2 -> parts[1].toIntOrNull()?.let { list.add(Entry(parts[0], parts[0], it)) }
                3 -> parts[2].toIntOrNull()?.let { list.add(Entry(parts[0], parts[1], it)) }
            }
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: ArrayList<Entry>) {
        val sb = StringBuilder()
        for (e in list) {
            sb.append(e.reading).append('\t').append(e.word).append('\t').append(e.count).append('\n')
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, sb.toString()).apply()
    }

    fun learn(ctx: Context, reading: String, word: String) {
        if (reading.isEmpty() || word.isEmpty()) return
        if (reading == word && reading.length < 2) return
        val list = load(ctx)
        val hit = list.firstOrNull { it.reading == reading && it.word == word }
        if (hit != null) {
            hit.count++
            list.remove(hit)
            list.add(hit)
        } else {
            list.add(Entry(reading, word, 1))
        }
        if (list.size > MAX_ENTRIES) {
            val victims = list.sortedBy { it.count }.take(list.size - MAX_ENTRIES)
            for (v in victims) list.remove(v)
        }
        save(ctx, list)
    }

    // prefix が空なら使用回数の多い語、そうでなければ読みが前方一致する語を返す
    fun suggest(ctx: Context, prefix: String, limit: Int = 5): List<String> {
        val list = load(ctx)
        if (list.isEmpty()) return emptyList()
        val hit = ArrayList<Entry>()
        for (e in list.asReversed()) {
            if (prefix.isEmpty()) hit.add(e)
            else if (e.reading.startsWith(prefix) && !(e.reading == prefix && e.word == prefix)) hit.add(e)
        }
        val out = ArrayList<String>()
        for (e in hit.sortedByDescending { it.count }) {
            if (!out.contains(e.word)) out.add(e.word)
            if (out.size >= limit) break
        }
        return out
    }

    fun clear(ctx: Context) {
        cache = null
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
