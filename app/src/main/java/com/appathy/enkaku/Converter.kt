package com.appathy.enkaku

import android.content.Context

// 内蔵辞書による変換。
// dict.txt   = 語彙（よみ→単語）
// kanji.txt  = 漢字モード用の単漢字テーブル（語彙とは別テーブル）
// grade.txt  = 学年別漢字（小学校モードの制限に使用）
object Converter {

    const val MODE_PREDICT = "PREDICT"
    const val MODE_KANJI = "KANJI"
    const val MODE_SCHOOL = "SCHOOL"
    const val MODE_TEXT = "TEXT"

    const val KANJI_MAX_LEN = 5   // 漢字/小学校モードで候補を出す最大文字数

    private var dictMap: LinkedHashMap<String, List<String>>? = null
    private var kanjiMap: LinkedHashMap<String, List<String>>? = null
    private var gradeSets: HashMap<Int, HashSet<Char>>? = null

    private fun readTable(ctx: Context, name: String): LinkedHashMap<String, List<String>> {
        val map = LinkedHashMap<String, List<String>>()
        try {
            ctx.assets.open(name).bufferedReader().use { br ->
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
            // 読めない場合は空テーブルで動作する
        }
        return map
    }

    private fun dict(ctx: Context): LinkedHashMap<String, List<String>> {
        dictMap?.let { return it }
        val m = readTable(ctx, "dict.txt"); dictMap = m; return m
    }

    private fun kanji(ctx: Context): LinkedHashMap<String, List<String>> {
        kanjiMap?.let { return it }
        val m = readTable(ctx, "kanji.txt"); kanjiMap = m; return m
    }

    private fun grades(ctx: Context): HashMap<Int, HashSet<Char>> {
        gradeSets?.let { return it }
        val map = HashMap<Int, HashSet<Char>>()
        try {
            ctx.assets.open("grade.txt").bufferedReader().use { br ->
                for (line in br.lineSequence()) {
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val i = line.indexOf('\t')
                    if (i <= 0) continue
                    val g = line.substring(0, i).toIntOrNull() ?: continue
                    map[g] = HashSet(line.substring(i + 1).toList())
                }
            }
        } catch (e: Throwable) {
        }
        gradeSets = map
        return map
    }

    // 指定学年までに習う漢字の集合
    private fun allowed(ctx: Context, grade: Int): HashSet<Char> {
        val all = HashSet<Char>()
        val g = grades(ctx)
        for (i in 1..grade) g[i]?.let { all.addAll(it) }
        return all
    }

    private fun isKanji(c: Char) = c.code in 0x4E00..0x9FFF

    private fun withinGrade(word: String, ok: HashSet<Char>): Boolean {
        for (c in word) if (isKanji(c) && !ok.contains(c)) return false
        return true
    }

    fun toKatakanaWord(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val code = c.code
            if (code in 0x3041..0x3096) sb.append((code + 0x60).toChar()) else sb.append(c)
        }
        return sb.toString()
    }

    // mode に応じた変換候補。完全一致 → 単漢字 → 前方一致 → カタカナ の順。
    fun candidates(ctx: Context, reading: String, mode: String, grade: Int, limit: Int = 8): List<String> {
        if (reading.isEmpty()) return emptyList()
        if (mode != MODE_KANJI && mode != MODE_SCHOOL) return emptyList()
        if (reading.length > KANJI_MAX_LEN) return emptyList()

        val out = ArrayList<String>()
        val d = dict(ctx)
        val k = kanji(ctx)

        d[reading]?.let { for (w in it) if (!out.contains(w)) out.add(w) }
        k[reading]?.let { for (w in it) if (!out.contains(w)) out.add(w) }

        if (out.size < limit) {
            val partial = ArrayList<Pair<String, List<String>>>()
            for ((key, v) in d) {
                if (key.length > reading.length && key.startsWith(reading)) partial.add(Pair(key, v))
            }
            partial.sortBy { it.first.length }
            for (p in partial) {
                for (w in p.second) if (!out.contains(w)) out.add(w)
                if (out.size >= limit * 2) break
            }
        }

        var result: List<String> = out
        if (mode == MODE_SCHOOL) {
            val ok = allowed(ctx, grade)
            result = out.filter { withinGrade(it, ok) }
        }

        val fin = ArrayList(result)
        val kata = toKatakanaWord(reading)
        if (kata != reading && !fin.contains(kata)) fin.add(kata)
        return if (fin.size > limit) fin.subList(0, limit) else fin
    }
}
