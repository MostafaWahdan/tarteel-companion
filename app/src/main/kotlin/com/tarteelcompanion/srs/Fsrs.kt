package com.tarteelcompanion.srs

import com.tarteelcompanion.data.model.ReviewGrade
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Clean-room implementation of the FSRS-6 scheduling algorithm
 * (https://github.com/open-spaced-repetition — algorithm by Jarrett Ye et al.).
 *
 * Deliberately NOT the vendored FSRS-Kotlin library the plan named: that upstream is
 * app-specific and carries a nondeterministic fuzz and an init-stability clamp bug
 * (recorded during execution). This implementation is deterministic (no fuzz),
 * locale-safe, and day-granular, which is all a passage-recall app needs.
 */
object Fsrs {

    /** FSRS-6 default parameters w0..w20 (untrained defaults). */
    val DEFAULT_PARAMS = doubleArrayOf(
        0.2120, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.0010,
        1.8722, 0.1666, 0.7960, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )

    const val REQUEST_RETENTION = 0.9
    const val MAX_INTERVAL_DAYS = 36_500

    object Phase {
        const val NEW = 0
        const val RELEARNING = 1
        const val REVIEW = 2
    }

    data class State(
        val stability: Double,
        val difficulty: Double,
        val phase: Int,
        val dueEpochDay: Long?,
        val lastReviewEpochDay: Long?,
    ) {
        val scheduledIntervalDays: Long
            get() = if (dueEpochDay != null && lastReviewEpochDay != null) {
                max(0, dueEpochDay - lastReviewEpochDay)
            } else 0
    }

    fun newCard(): State = State(0.0, 0.0, Phase.NEW, null, null)

    private val w = DEFAULT_PARAMS
    private val decay = -w[20]
    private val factor = REQUEST_RETENTION.pow(1.0 / decay) - 1.0

    /** Power forgetting curve: probability of recall after [elapsedDays] at [stability]. */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + factor * elapsedDays / stability).pow(decay)
    }

    /** Interval (days) at which retrievability decays to the request retention. */
    fun nextIntervalDays(stability: Double): Int {
        val raw = stability / factor * (REQUEST_RETENTION.pow(1.0 / decay) - 1.0)
        return raw.roundToInt().coerceIn(1, MAX_INTERVAL_DAYS)
    }

    /**
     * Applies a graded review on [todayEpochDay] and returns the next state.
     * Deterministic: same inputs always produce the same schedule.
     */
    fun grade(state: State, grade: ReviewGrade, todayEpochDay: Long): State {
        val g = gradeValue(grade)
        val next = when (state.phase) {
            Phase.NEW -> initialState(g)
            Phase.RELEARNING -> shortTermState(state, g)
            else -> reviewState(state, g, todayEpochDay)
        }

        val (phase, intervalDays) = when (grade) {
            // Again always relearns today; Hard in learning phases also stays today.
            ReviewGrade.AGAIN -> Phase.RELEARNING to 0L
            ReviewGrade.HARD ->
                if (state.phase == Phase.REVIEW) {
                    Phase.REVIEW to nextIntervalDays(next.first).toLong()
                } else {
                    Phase.RELEARNING to 0L
                }
            else -> Phase.REVIEW to nextIntervalDays(next.first).toLong()
        }

        return State(
            stability = next.first,
            difficulty = next.second,
            phase = phase,
            dueEpochDay = todayEpochDay + intervalDays,
            lastReviewEpochDay = todayEpochDay,
        )
    }

    /**
     * Applies a lapse caused by a real-world recitation failure (a new occurrence on a
     * scheduled spot) rather than a graded review: forget-transition on stability,
     * difficulty moves as an Again, card returns to relearning due today (plan U7
     * invariant 4 — live failure outranks any pending quiz).
     */
    fun occurrenceLapse(state: State, todayEpochDay: Long): State {
        if (state.phase == Phase.NEW) return state
        val elapsed = elapsedDays(state, todayEpochDay)
        val r = retrievability(elapsed, state.stability)
        return State(
            stability = forgetStability(state.difficulty, state.stability, r),
            difficulty = nextDifficulty(state.difficulty, gradeValue(ReviewGrade.AGAIN)),
            phase = Phase.RELEARNING,
            dueEpochDay = todayEpochDay,
            lastReviewEpochDay = state.lastReviewEpochDay,
        )
    }

    private fun elapsedDays(state: State, todayEpochDay: Long): Double =
        state.lastReviewEpochDay?.let { max(0L, todayEpochDay - it).toDouble() } ?: 0.0

    private fun gradeValue(grade: ReviewGrade): Int = when (grade) {
        ReviewGrade.AGAIN -> 1
        ReviewGrade.HARD -> 2
        ReviewGrade.GOOD -> 3
        ReviewGrade.EASY -> 4
    }

    private fun initialState(g: Int): Pair<Double, Double> =
        initStability(g) to initDifficulty(g)

    private fun shortTermState(state: State, g: Int): Pair<Double, Double> {
        if (state.stability <= 0.0) return initialState(g)
        var sinc = exp(w[17] * (g - 3 + w[18])) * state.stability.pow(-w[19])
        if (g >= 3) sinc = max(sinc, 1.0)
        return (state.stability * sinc) to nextDifficulty(state.difficulty, g)
    }

    private fun reviewState(state: State, g: Int, todayEpochDay: Long): Pair<Double, Double> {
        val elapsed = elapsedDays(state, todayEpochDay)
        val r = retrievability(elapsed, state.stability)
        val s = if (g == 1) {
            forgetStability(state.difficulty, state.stability, r)
        } else {
            recallStability(state.difficulty, state.stability, r, g)
        }
        return s to nextDifficulty(state.difficulty, g)
    }

    private fun initStability(g: Int): Double = w[g - 1].coerceAtLeast(0.1)

    private fun initDifficulty(g: Int): Double =
        (w[4] - exp(w[5] * (g - 1)) + 1.0).coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, g: Int): Double {
        val delta = -w[6] * (g - 3)
        val damped = d + delta * (10.0 - d) / 9.0
        val reverted = w[7] * initDifficulty(4) + (1.0 - w[7]) * damped
        return reverted.coerceIn(1.0, 10.0)
    }

    private fun recallStability(d: Double, s: Double, r: Double, g: Int): Double {
        val hardPenalty = if (g == 2) w[15] else 1.0
        val easyBonus = if (g == 4) w[16] else 1.0
        val growth = exp(w[8]) * (11.0 - d) * s.pow(-w[9]) *
            (exp((1.0 - r) * w[10]) - 1.0) * hardPenalty * easyBonus
        return s * (1.0 + growth)
    }

    private fun forgetStability(d: Double, s: Double, r: Double): Double {
        val sMin = s / exp(w[17] * w[18])
        val result = w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) *
            exp((1.0 - r) * w[14])
        return min(result, sMin).coerceAtLeast(0.01)
    }
}
