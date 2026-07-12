package com.tarteelcompanion.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarteelcompanion.TarteelApp
import com.tarteelcompanion.data.MistakeRepository
import com.tarteelcompanion.data.entity.encodeWordRef
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.data.model.ReviewGrade
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.quran.WordRef
import com.tarteelcompanion.srs.SchedulingPolicy
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface StudyUiState {
    data object Loading : StudyUiState

    /** Nothing due: offer review-ahead or point at Import (M4). */
    data class Empty(val hasAnyActiveSpots: Boolean) : StudyUiState

    data class Reviewing(
        val card: StudyCard,
        val index: Int,
        val total: Int,
        val revealed: Boolean,
        /** True when this session is a review-ahead (nothing was due) — M4. */
        val reviewAhead: Boolean,
    ) : StudyUiState

    data class SessionDone(val graded: Int) : StudyUiState
}

/**
 * Drives F2 (study session): due queue → per-ayah cards → reveal → self-grade fan-out
 * through the single scheduling authority (U7).
 */
class StudyViewModel(
    private val quranDeferred: Deferred<QuranRepository>,
    private val mistakes: MistakeRepository,
    private val policy: SchedulingPolicy,
) : ViewModel() {

    private val _state = MutableStateFlow<StudyUiState>(StudyUiState.Loading)
    val state: StateFlow<StudyUiState> = _state

    private var cards: List<StudyCard> = emptyList()
    private var index = 0
    private var graded = 0
    private var reviewAhead = false

    private fun today() = LocalDate.now().toEpochDay()
    private fun now() = System.currentTimeMillis()

    init {
        loadQueue()
    }

    fun loadQueue(ahead: Boolean = false) {
        _state.value = StudyUiState.Loading
        viewModelScope.launch {
            val quran = quranDeferred.await()
            val due = policy.studyQueue(today(), now())
            val pool = if (due.isEmpty() && ahead) {
                policy.quizExcludedActiveSpots(today(), now())
            } else {
                due
            }
            if (pool.isEmpty()) {
                val anyActive = policy.hasActiveSpots()
                _state.value = StudyUiState.Empty(anyActive)
                return@launch
            }
            reviewAhead = due.isEmpty() && ahead
            val spots = pool.map { spot ->
                CardSpot(spot.wordRef, mistakes.effectiveType(spot.wordRef) ?: MistakeType.WRONG_WORD)
            }
            cards = CardBuilder(quran).build(spots)
            index = 0
            graded = 0
            publish(revealed = false)
        }
    }

    fun reveal() = publish(revealed = true)

    /** One grade per card, fanned out to every contained spot (R19). */
    fun grade(grade: ReviewGrade) {
        val card = cards.getOrNull(index) ?: return
        viewModelScope.launch {
            for (spot in card.spots) {
                policy.studyGrade(encodeWordRef(spot.ref), grade, today(), now())
            }
            graded += card.spots.size
            index++
            if (index >= cards.size) {
                _state.value = StudyUiState.SessionDone(graded)
            } else {
                publish(revealed = false)
            }
        }
    }

    fun suspendSpot(ref: WordRef) = viewModelScope.launch {
        mistakes.suspend(ref)
        skipCurrentCard()
    }

    fun deleteSpot(ref: WordRef) = viewModelScope.launch {
        mistakes.delete(ref)
        skipCurrentCard()
    }

    private fun skipCurrentCard() {
        index++
        if (index >= cards.size) {
            _state.value = StudyUiState.SessionDone(graded)
        } else {
            publish(revealed = false)
        }
    }

    private fun publish(revealed: Boolean) {
        val card = cards.getOrNull(index) ?: return
        _state.value = StudyUiState.Reviewing(card, index + 1, cards.size, revealed, reviewAhead)
    }

    companion object {
        fun factory(app: TarteelApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StudyViewModel(app.quran, app.mistakes, app.policy) as T
        }
    }
}
