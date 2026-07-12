package com.tarteelcompanion.quiz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarteelcompanion.TarteelApp
import com.tarteelcompanion.data.MistakeRepository
import com.tarteelcompanion.data.entity.encodeWordRef
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.data.model.ReviewGrade
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.srs.SchedulingPolicy
import com.tarteelcompanion.study.CardBuilder
import com.tarteelcompanion.study.CardSpot
import com.tarteelcompanion.study.StudyCard
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Per-card quiz outcome for the summary screen (R17). */
data class QuizResult(val card: StudyCard, val grade: ReviewGrade) {
    val returnedToStudy: Boolean get() = grade == ReviewGrade.AGAIN || grade == ReviewGrade.HARD
}

sealed interface QuizUiState {
    data object Loading : QuizUiState

    /** Nothing quiz-pending right now (M4-style designed empty state). */
    data object Empty : QuizUiState

    data class Question(
        val card: StudyCard,
        val index: Int,
        val total: Int,
        /** FRONT → REVEALED (no aids, R16) → GRADED (aids visible, then Next). */
        val phase: Phase,
        val grade: ReviewGrade? = null,
    ) : QuizUiState {
        enum class Phase { FRONT, REVEALED, GRADED }
    }

    data class Summary(val results: List<QuizResult>) : QuizUiState {
        val total: Int get() = results.size
        val passed: Int get() = results.count { !it.returnedToStudy }
    }
}

/**
 * F3: cold-recall quiz. Same cards as study, aids suppressed until after grading
 * (R16 — suppression is a flag on the same CardBuilder output, not a second pipeline).
 * Grades route through the U7 policy, which replays from the pre-study snapshot.
 * Mid-quiz abandonment keeps committed grades; ungraded spots stay pending (M5).
 */
class QuizViewModel(
    private val quranDeferred: Deferred<QuranRepository>,
    private val mistakes: MistakeRepository,
    private val policy: SchedulingPolicy,
    /** Session size cap (plan: pending quizzes merge, oldest first, capped). */
    private val sessionCap: Int = 30,
) : ViewModel() {

    private val _state = MutableStateFlow<QuizUiState>(QuizUiState.Loading)
    val state: StateFlow<QuizUiState> = _state

    private var cards: List<StudyCard> = emptyList()
    private var index = 0
    private val results = mutableListOf<QuizResult>()

    private fun today() = LocalDate.now().toEpochDay()
    private fun now() = System.currentTimeMillis()

    init {
        loadQueue()
    }

    fun loadQueue() {
        _state.value = QuizUiState.Loading
        viewModelScope.launch {
            val quran = quranDeferred.await()
            val pending = policy.quizQueue(today(), now()).take(sessionCap)
            if (pending.isEmpty()) {
                _state.value = QuizUiState.Empty
                return@launch
            }
            val spots = pending.map { spot ->
                CardSpot(spot.wordRef, mistakes.effectiveType(spot.wordRef) ?: MistakeType.WRONG_WORD)
            }
            cards = CardBuilder(quran).build(spots)
            index = 0
            results.clear()
            publish(QuizUiState.Question.Phase.FRONT)
        }
    }

    fun reveal() = publish(QuizUiState.Question.Phase.REVEALED)

    fun grade(grade: ReviewGrade) {
        val card = cards.getOrNull(index) ?: return
        viewModelScope.launch {
            for (spot in card.spots) {
                policy.quizGrade(encodeWordRef(spot.ref), grade, today(), now())
            }
            results += QuizResult(card, grade)
            publish(QuizUiState.Question.Phase.GRADED, grade)
        }
    }

    fun next() {
        index++
        if (index >= cards.size) {
            _state.value = QuizUiState.Summary(results.toList())
        } else {
            publish(QuizUiState.Question.Phase.FRONT)
        }
    }

    private fun publish(phase: QuizUiState.Question.Phase, grade: ReviewGrade? = null) {
        val card = cards.getOrNull(index) ?: return
        _state.value = QuizUiState.Question(card, index + 1, cards.size, phase, grade)
    }

    companion object {
        fun factory(app: TarteelApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                QuizViewModel(app.quran, app.mistakes, app.policy) as T
        }
    }
}
