package com.appathy.enkaku

import android.content.Context

// 内蔵辞書（assets/dict.txt）によるかな漢字変換。
// 他IMEの学習辞書はAndroidの仕様上読めないため、辞書は本アプリに同梱している。
object Converter {

    private var dict: LinkedHashMap<String, List<String>>? = null

    private fun dict(ctx: Context): LinkedHashMap<String, List<String>> {
        dict?.let { return it }
        val map = LinkedHashMap<String, List<String>>()
        try {
            ctx.assets.open("dict.txt").bufferedReader().use { br ->
                for (line in br.lineSequence()) {
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val i = line.indexOf('\t')
                    if (i <= 0) continue
                    val reading = line.substring(0, i)
                    val words = line.substring(i + 1).split(",").filter { it.isNotEmpty() }
                    if (words.isNotEmpty()) map[reading] = words
                }
            }
        } catch (e: Throwable) {
            // 辞書が読めない場合はかな候補だけで動作する
        }
        dict = map
        return map
    }

    fun toKatakanaWord(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val code = c.code
            if (code in 0x3041..0x3096) sb.append((code + 0x60).toChar()) else sb.append(c)
        }
        return sb.toString()
    }

    // reading に対する変換候補。完全一致 → 前方一致（短い読み順）→ カタカナ の順。
    fun convert(ctx: Context, reading: String, limit: Int = 8): List<String> {
        if (reading.isEmpty()) return emptyList()
        val map = dict(ctx)
        val out = ArrayList<String>()

        map[reading]?.let { for (w in it) if (!out.contains(w)) out.add(w) }

        if (out.size < limit) {
            val partial = ArrayList<Pair<String, List<String>>>()
            for ((k, v) in map) {
                if (k.length > reading.length && k.startsWith(reading)) partial.add(Pair(k, v))
            }
            partial.sortBy { it.first.length }
            for (p in partial) {
                for (w in p.second) {
                    if (!out.contains(w)) out.add(w)
                    if (out.size >= limit) break
                }
                if (out.size >= limit) break
            }
        }

        val kata = toKatakanaWord(reading)
        if (kata != reading && !out.contains(kata)) out.add(kata)

        return if (out.size > limit) out.subList(0, limit) else out
    }
}
