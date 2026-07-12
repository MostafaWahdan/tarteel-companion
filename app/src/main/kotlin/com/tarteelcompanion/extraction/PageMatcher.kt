package com.tarteelcompanion.extraction

import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.quran.QuranWord
import kotlin.math.abs

/**
 * Identifies the mushaf page and anchors ink runs to dataset words — no OCR, no
 * templates (spike verdict b, revised after corpus diagnostics): justified rendering
 * lets words touch AND words split into several runs at non-joining letters, so the
 * mapping is many-to-many. Each page's words have predictable rendered widths (sum of
 * letter width classes), and a two-move DP aligns observed ink runs with expected
 * tokens (words + ayah-marker ornaments): a run may render several tokens (touching
 * words), or one token may span several same-line runs (intra-word gaps). The
 * normalized alignment cost selects the page (with a runner-up margin) and gates
 * extraction (poor fit → NEEDS_MANUAL).
 */
class PageMatcher(private val quran: QuranRepository) {

    /** One expected render token: a dataset word, or the ayah-marker ornament after it. */
    data class Token(val wordIndex: Int?, val units: Double) {
        val isMarker: Boolean get() = wordIndex == null
    }

    data class PageAlignment(
        val pageNumber: Int,
        val words: List<QuranWord>,
        val tokens: List<Token>,
        /** For each ink run, the token index range it renders (may repeat across runs). */
        val runTokenRanges: List<IntRange>,
        val cost: Double,
    )

    companion object {
        private const val MARKER_UNITS = 2.4
        private const val MAX_TOKENS_PER_RUN = 8
        private const val MAX_RUNS_PER_TOKEN = 4
        const val ACCEPT_COST = 0.15
        const val RUNNER_UP_MARGIN = 1.15

        /** Narrow letters take roughly half a standard letter's advance in Uthmani script. */
        private val NARROW = "اٱأإآلردذزونى".toSet()

        /** Estimated advance units of a word's skeleton (diacritics/digits are free). */
        fun wordUnits(text: String): Double {
            var units = 0.0
            for (ch in text) {
                when {
                    ch in 'ً'..'ٟ' || ch == 'ٰ' || ch in 'ۖ'..'ۭ' || ch == 'ـ' -> {}
                    ch in '٠'..'٩' || ch == ' ' -> {} // ayah digits render inside the marker
                    ch in NARROW -> units += 0.55
                    else -> units += 1.0
                }
            }
            return units.coerceAtLeast(0.55)
        }
    }

    private val pageTokens: List<List<Token>> by lazy {
        (1..604).map { pageNumber ->
            val words = quran.page(pageNumber)!!.lines.flatMap { it.words }
            val tokens = mutableListOf<Token>()
            words.forEachIndexed { i, word ->
                tokens.add(Token(i, wordUnits(word.text)))
                if (word.text.any { it in '٠'..'٩' }) tokens.add(Token(null, MARKER_UNITS))
            }
            tokens
        }
    }

    private class DpResult(val cost: Double, val runRanges: List<IntRange>)

    /**
     * Stage 1: line-level DP. Lines are justified and words never hyphenate, so each
     * line consumes a contiguous token range whose unit total should match the line's
     * total ink width. Aggregates are immune to word-split/touch noise, which is what
     * makes this the page-discriminating stage. Returns (cost, token count per line).
     */
    private fun alignLines(lineWidths: IntArray, tokens: List<Token>): Pair<Double, IntArray>? {
        val m = lineWidths.size
        val n = tokens.size
        if (m == 0 || n < m) return null

        val totalWidth = lineWidths.sum().toDouble()
        val totalUnits = tokens.sumOf { it.units }
        val pxPerUnit = totalWidth / totalUnits

        val prefixUnits = DoubleArray(n + 1)
        for (j in 1..n) prefixUnits[j] = prefixUnits[j - 1] + tokens[j - 1].units

        val maxPerLine = (n / m) * 3 + 8
        val big = Double.MAX_VALUE / 4
        val dp = Array(m + 1) { DoubleArray(n + 1) { big } }
        val choice = Array(m + 1) { IntArray(n + 1) }
        dp[0][0] = 0.0

        for (l in 1..m) {
            val width = lineWidths[l - 1].toDouble()
            for (j in l..n) {
                for (k in 1..minOf(maxPerLine, j)) {
                    val prev = dp[l - 1][j - k]
                    if (prev >= big) continue
                    val expected = (prefixUnits[j] - prefixUnits[j - k]) * pxPerUnit
                    val cost = prev + relErr(width, expected)
                    if (cost < dp[l][j]) {
                        dp[l][j] = cost
                        choice[l][j] = k
                    }
                }
            }
        }
        if (dp[m][n] >= big) return null

        val counts = IntArray(m)
        var j = n
        for (l in m downTo 1) {
            counts[l - 1] = choice[l][j]
            j -= choice[l][j]
        }
        return (dp[m][n] / m) to counts
    }

    /**
     * Two-move DP. dp[i][j] = best (cost, moves) explaining the first i runs with the
     * first j tokens. Move A: run i renders tokens (j-k+1..j). Move B: token j spans
     * runs (i-k+1..i), which must share a line; expected width includes the gaps.
     */
    private fun align(runs: List<Segmentation.WordCluster>, tokens: List<Token>): DpResult? {
        val m = runs.size
        val n = tokens.size
        if (m == 0 || n == 0) return null

        val totalWidth = runs.sumOf { it.width }.toDouble()
        val totalUnits = tokens.sumOf { it.units }
        val pxPerUnit = totalWidth / totalUnits

        val big = Double.MAX_VALUE / 4
        val dp = Array(m + 1) { DoubleArray(n + 1) { big } }
        val moveRuns = Array(m + 1) { IntArray(n + 1) } // runs consumed by the chosen move
        val moveTokens = Array(m + 1) { IntArray(n + 1) } // tokens consumed
        val movesCount = Array(m + 1) { IntArray(n + 1) }
        dp[0][0] = 0.0

        val prefixUnits = DoubleArray(n + 1)
        for (j in 1..n) prefixUnits[j] = prefixUnits[j - 1] + tokens[j - 1].units

        for (i in 1..m) {
            val run = runs[i - 1]
            for (j in 1..n) {
                // Move A: run i-1 renders k tokens.
                val maxK = minOf(MAX_TOKENS_PER_RUN, j)
                for (k in 1..maxK) {
                    val prev = dp[i - 1][j - k]
                    if (prev >= big) continue
                    val expected = (prefixUnits[j] - prefixUnits[j - k]) * pxPerUnit
                    val cost = prev + relErr(run.width.toDouble(), expected)
                    if (cost < dp[i][j]) {
                        dp[i][j] = cost
                        moveRuns[i][j] = 1
                        moveTokens[i][j] = k
                        movesCount[i][j] = movesCount[i - 1][j - k] + 1
                    }
                }
                // Move B: token j-1 spans k runs on one line (span includes the gaps).
                var spanMinX = run.minX
                var spanMaxX = run.maxX
                for (k in 2..minOf(MAX_RUNS_PER_TOKEN, i)) {
                    val first = runs[i - k]
                    if (first.line != run.line) break
                    // RTL: earlier runs are to the right.
                    if (first.minX > spanMinX) spanMaxX = maxOf(spanMaxX, first.maxX) else break
                    spanMinX = minOf(spanMinX, first.minX)
                    val prev = dp[i - k][j - 1]
                    if (prev >= big) continue
                    val expected = tokens[j - 1].units * pxPerUnit
                    val cost = prev + relErr((spanMaxX - spanMinX + 1).toDouble(), expected)
                    if (cost < dp[i][j]) {
                        dp[i][j] = cost
                        moveRuns[i][j] = k
                        moveTokens[i][j] = 1
                        movesCount[i][j] = movesCount[i - k][j - 1] + 1
                    }
                }
            }
        }
        if (dp[m][n] >= big) return null

        val ranges = arrayOfNulls<IntRange>(m)
        var i = m
        var j = n
        while (i > 0) {
            val rk = moveRuns[i][j]
            val tk = moveTokens[i][j]
            val range = (j - tk) until j
            repeat(rk) { ranges[i - 1 - it] = range }
            i -= rk
            j -= tk
        }
        return DpResult(dp[m][n] / movesCount[m][n], ranges.map { it!! })
    }

    private fun relErr(observed: Double, expected: Double): Double =
        abs(observed - expected) / maxOf(observed, expected, 1.0)

    private fun lineWidthsOf(runs: List<Segmentation.WordCluster>): IntArray {
        val lineCount = (runs.maxOfOrNull { it.line } ?: -1) + 1
        val widths = IntArray(lineCount)
        for (run in runs) widths[run.line] += run.width
        return widths
    }

    /**
     * Aligns the runs against ONE known page (empirical finding, recorded in the spike
     * doc: the width model cannot identify a page blind — its job is anchoring within
     * a page the user or batch context supplies). Null when even the known page fits
     * poorly (wrong page / partial screenshot) — the caller gates to manual.
     */
    fun alignToPage(runs: List<Segmentation.WordCluster>, page: Int): PageAlignment? {
        if (page !in 1..604) return null
        val lineWidths = lineWidthsOf(runs)
        if (lineWidths.isEmpty()) return null
        val tokens = pageTokens[page - 1]

        // Scale sanity: a standard letter's advance is a modest fraction of the line
        // height. The DP's self-normalizing px-per-unit would otherwise let a short
        // page (few tokens) "fit" a dense screenshot at an absurd glyph scale.
        val medianLineHeight = runs.map { it.lineBottom - it.lineTop }.sorted()[runs.size / 2].toDouble()
        val pxPerUnit = lineWidths.sum().toDouble() / tokens.sumOf { it.units }
        if (pxPerUnit !in medianLineHeight * 0.08..medianLineHeight * 0.8) return null

        val (lineCost, counts) = alignLines(lineWidths, tokens) ?: return null
        if (lineCost > ACCEPT_COST) return null

        // Stage 2: within each line, run-level DP over that line's token range only
        // (for word anchoring of colored marks); proportional fallback when infeasible.
        val runRanges = arrayOfNulls<IntRange>(runs.size)
        var tokenStart = 0
        for ((lineIdx, count) in counts.withIndex()) {
            val lineRuns = runs.withIndex().filter { it.value.line == lineIdx }
            val lineTokens = tokens.subList(tokenStart, tokenStart + count)
            val aligned = align(lineRuns.map { it.value }, lineTokens)
            if (aligned == null) {
                lineRuns.forEach { (i, _) -> runRanges[i] = tokenStart until (tokenStart + count) }
            } else {
                lineRuns.forEachIndexed { local, (i, _) ->
                    val r = aligned.runRanges[local]
                    runRanges[i] = (r.first + tokenStart)..(r.last + tokenStart)
                }
            }
            tokenStart += count
        }

        val words = quran.page(page)!!.lines.flatMap { it.words }
        return PageAlignment(page, words, tokens, runRanges.map { it!! }, lineCost)
    }
}
