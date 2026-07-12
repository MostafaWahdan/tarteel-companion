package com.tarteelcompanion.study

/**
 * Word-level diff between similar verses (plan U9): highlights where wordings diverge
 * after normalization, so a card can show exactly which words distinguish confusable
 * ayat. Deterministic — dataset membership decides WHAT is compared (R11); this only
 * decides which words to highlight.
 */
object MutashabihatDiffer {

    /** A word with a flag marking whether it diverges from the compared text. */
    data class DiffWord(val text: String, val divergent: Boolean)

    /**
     * Marks, for each side, the words that do not appear (in order) in the other side.
     * Divergence only in diacritics counts as identical — huffaz confuse wordings, and
     * tashkeel-only differences would light up entire verses (plan U9 edge case).
     */
    fun diff(left: List<String>, right: List<String>): Pair<List<DiffWord>, List<DiffWord>> {
        val leftKeys = left.map(::normalize)
        val rightKeys = right.map(::normalize)
        val inLcs = lcsMembership(leftKeys, rightKeys)
        return Pair(
            left.mapIndexed { i, w -> DiffWord(w, !inLcs.first[i]) },
            right.mapIndexed { i, w -> DiffWord(w, !inLcs.second[i]) },
        )
    }

    /**
     * Normalizes an Uthmani word to a comparison skeleton: strips harakat, Quranic
     * annotation marks, tatweel, superscript alef, and trailing ayah-number digits;
     * folds alef/hamza carriers together.
     */
    fun normalize(word: String): String = buildString {
        for (ch in word) {
            when {
                ch in 'ً'..'ٟ' -> {} // harakat + combining marks
                ch == 'ٰ' -> {} // superscript alef
                ch in 'ۖ'..'ۭ' -> {} // Quranic annotation signs
                ch == 'ـ' -> {} // tatweel
                ch in '٠'..'٩' || ch in '۰'..'۹' -> {} // ayah digits
                ch == ' ' -> {}
                ch in "آأإٱ" -> append('ا') // alef variants → alef
                ch == 'ة' -> append('ه') // ta marbuta → ha (skeleton match)
                else -> append(ch)
            }
        }
    }

    /** LCS membership flags for both sequences (classic DP, sequences are short). */
    private fun lcsMembership(a: List<String>, b: List<String>): Pair<BooleanArray, BooleanArray> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val inA = BooleanArray(a.size)
        val inB = BooleanArray(b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { inA[i] = true; inB[j] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return inA to inB
    }
}
