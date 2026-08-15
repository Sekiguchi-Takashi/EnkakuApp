package com.appathy.enkaku

import android.content.Context

// 自前の入力履歴による予測（他IMEの履歴はAndroidの仕様上読めないため本アプリ内で学習する）
object Predictor {

    private const val PREF = "enkaku_pred"
    private const val KEY = "words"
    private const val MAX_ENTRIES = 400

    private var cache: LinkedHashMap<String, Int>? = null

    private fun load(ctx: Context): LinkedHashMap<String, Int> {
        cache?.let { return it }
        val map = LinkedHashMap<String, Int>()
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        for (line in raw.split("\n")) {
            if (line.isEmpty()) continue
            val idx = line.lastIndexOf('\t')
            if (idx <= 0) continue
            val w = line.substring(0, idx)
            val n = line.substring(idx + 1).toIntOrNull() ?: continue
            map[w] = n
        }
        cache = map
        return map
    }

    private fun save(ctx: Context, map: LinkedHashMap<String, Int>) {
        val sb = StringBuilder()
        for ((w, n) in map) {
            sb.append(w).append('\t').append(n).append('\n')
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, sb.toString()).apply()
    }

    fun learn(ctx: Context, word: String) {
        if (word.length < 2) return
        val map = load(ctx)
        val n = (map[word] ?: 0) + 1
        map.remove(word)
        map[word] = n
        if (map.size > MAX_ENTRIES) {
            val victims = map.entries.sortedBy { it.value }.take(map.size - MAX_ENTRIES)
            for (v in victims) map.remove(v.key)
        }
        save(ctx, map)
    }

    // prefix が空なら使用回数の多い語、そうでなければ前方一致の語を返す
    fun suggest(ctx: Context, prefix: String, limit: Int = 5): List<String> {
        val map = load(ctx)
        if (map.isEmpty()) return emptyList()
        val entries = map.entries.toList().asReversed()
        val hit = ArrayList<Pair<String, Int>>()
        for (e in entries) {
            if (prefix.isEmpty()) {
                hit.add(Pair(e.key, e.value))
            } else if (e.key.startsWith(prefix) && e.key != prefix) {
                hit.add(Pair(e.key, e.value))
            }
        }
        return hit.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    fun clear(ctx: Context) {
        cache = null
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
