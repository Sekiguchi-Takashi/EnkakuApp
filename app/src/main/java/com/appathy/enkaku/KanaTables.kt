package com.appathy.enkaku

// フリック方向: 0=中央(tap) 1=左 2=上 3=右 4=下
object KanaTables {

    // 12キーのフリック割り当て（ひらがな）。index順に画面の3x4グリッドへ配置する。
    // 各配列 = [中央, 左, 上, 右, 下]
    val flick: List<Array<String>> = listOf(
        arrayOf("あ", "い", "う", "え", "お"),
        arrayOf("か", "き", "く", "け", "こ"),
        arrayOf("さ", "し", "す", "せ", "そ"),
        arrayOf("た", "ち", "つ", "て", "と"),
        arrayOf("な", "に", "ぬ", "ね", "の"),
        arrayOf("は", "ひ", "ふ", "へ", "ほ"),
        arrayOf("ま", "み", "む", "め", "も"),
        arrayOf("や", "（", "ゆ", "）", "よ"),
        arrayOf("ら", "り", "る", "れ", "ろ"),
        // わ行 + 句読点。ここでは特殊キー(DAKUTEN/わ/句読点)をグリッド最下段に置くため
        // わキーのみflickを持たせ、両隣は特殊キーとしてView側で扱う。
        arrayOf("わ", "を", "ん", "ー", "〜"),
        arrayOf("、", "。", "？", "！", "…")
    )

    // グリッド最下段左端は濁点/小書きキー。flickには含めず特殊扱い。
    // グリッド配置（12スロット、3列×4行）:
    //  0あ 1か 2さ
    //  3た 4な 5は
    //  6ま 7や 8ら
    //  D  9わ 10、       ← Dは濁点/小書き特殊キー
    // よってflickリストのindexとグリッドスロットの対応をView側で定義する。

    // 濁点/半濁点/小書きの巡回変換。順に押すと次へ、末尾まで行くと戻る。
    private val cycles: List<List<String>> = listOf(
        listOf("か", "が"), listOf("き", "ぎ"), listOf("く", "ぐ"), listOf("け", "げ"), listOf("こ", "ご"),
        listOf("さ", "ざ"), listOf("し", "じ"), listOf("す", "ず"), listOf("せ", "ぜ"), listOf("そ", "ぞ"),
        listOf("た", "だ"), listOf("ち", "ぢ"), listOf("つ", "っ", "づ"), listOf("て", "で"), listOf("と", "ど"),
        listOf("は", "ば", "ぱ"), listOf("ひ", "び", "ぴ"), listOf("ふ", "ぶ", "ぷ"),
        listOf("へ", "べ", "ぺ"), listOf("ほ", "ぼ", "ぽ"),
        listOf("あ", "ぁ"), listOf("い", "ぃ"), listOf("う", "ぅ", "ゔ"), listOf("え", "ぇ"), listOf("お", "ぉ"),
        listOf("や", "ゃ"), listOf("ゆ", "ゅ"), listOf("よ", "ょ"), listOf("わ", "ゎ")
    )

    // 直前1文字を次の変形へ。該当が無ければnull。
    fun cycleChar(c: String): String? {
        for (grp in cycles) {
            val idx = grp.indexOf(c)
            if (idx >= 0) return grp[(idx + 1) % grp.size]
        }
        return null
    }

    // ひらがな→カタカナ
    fun toKatakana(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val code = ch.code
            if (code in 0x3041..0x3096) sb.append((code + 0x60).toChar()) else sb.append(ch)
        }
        return sb.toString()
    }

    // ABCモードのマルチタップ割り当て（3x4グリッド）
    val latin: List<String> = listOf(
        "@-_/", "abc", "def",
        "ghi", "jkl", "mno",
        "pqrs", "tuv", "wxyz",
        "'\",", ".?!", " "
    )

    // 123モードの直接キー（3x4グリッド）
    val symbols: List<String> = listOf(
        "1", "2", "3",
        "4", "5", "6",
        "7", "8", "9",
        "*", "0", "#"
    )

    // ---- スライド展開キーボード用 ----

    // 行頭（画面に横並びで出る）
    val gyoHeads: List<String> = listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ")

    // 各行の あ-い-う-え-お 順（可変長。や行は3、わ行は を/ん/ー/〜 を含む）
    val gyo: List<List<String>> = listOf(
        listOf("あ", "い", "う", "え", "お"),
        listOf("か", "き", "く", "け", "こ"),
        listOf("さ", "し", "す", "せ", "そ"),
        listOf("た", "ち", "つ", "て", "と"),
        listOf("な", "に", "ぬ", "ね", "の"),
        listOf("は", "ひ", "ふ", "へ", "ほ"),
        listOf("ま", "み", "む", "め", "も"),
        listOf("や", "ゆ", "よ"),
        listOf("ら", "り", "る", "れ", "ろ"),
        listOf("わ", "を", "ん", "ー", "〜")
    )

    // 行iの展開ストリップ（左→右）= [カタカナを外側から] + [行頭ひらがな] + [残りのひらがな]
    // 例 あ: オ エ ウ イ ア あ い う え お
    fun expandStrip(i: Int): List<String> {
        val row = gyo[i]
        val left = ArrayList<String>()
        for (j in row.indices.reversed()) left.add(toKatakana(row[j]))
        val right = ArrayList<String>()
        for (j in 1 until row.size) right.add(row[j])
        val out = ArrayList<String>()
        out.addAll(left)
        out.add(row[0])
        out.addAll(right)
        return out
    }

    // 記号ストリップ（. と @ を先頭付近に）
    val symbolStrip: List<String> =
        listOf(".", "@", "/", "_", ":", "-", "、", "。", "？", "！", "〜")

    // 数字ストリップ
    val digitStrip: List<String> = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")

    // ---- 英字モード ----
    // 展開グループ: a=a〜h / i=i〜q / r=r〜z（大文字も同じ区切り）
    val alphaHeads: List<String> = listOf("a", "i", "r", "A", "I", "R")

    val alphaGroups: List<List<String>> = listOf(
        "abcdefgh".map { it.toString() },
        "ijklmnopq".map { it.toString() },
        "rstuvwxyz".map { it.toString() },
        "ABCDEFGH".map { it.toString() },
        "IJKLMNOPQ".map { it.toString() },
        "RSTUVWXYZ".map { it.toString() }
    )

    // ローマ数字ストリップ（Ⅰ〜Ⅹ。ローマ数字に0は存在しない）
    val romanStrip: List<String> =
        listOf("\u2160", "\u2161", "\u2162", "\u2163", "\u2164", "\u2165", "\u2166", "\u2167", "\u2168", "\u2169")
}
