package com.tarteelcompanion.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarteelcompanion.TarteelApp
import com.tarteelcompanion.data.MistakeRepository
import com.tarteelcompanion.data.entity.encodeWordRef
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.data.model.ReviewGrade
import com.tarteelcompanion.mnemonics.GenerationWorker
import com.tarteelcompanion.mnemonics.MnemonicEntity
import com.tarteelcompanion.mnemonics.MnemonicRepository
import com.tarteelcompanion.mnemonics.MnemonicStatus
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.quran.WordRef
import com.tarteelcompanion.srs.SchedulingPolicy
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
        /** Mnemonics for the card's mutashabihat groups, in group order (R12). */
        val mnemonics: List<MnemonicEntity> = emptyList(),
    ) : StudyUiState

    data class SessionDone(val graded: Int) : StudyUiState
}

/**
 * Drives F2 (study session): due queue → per-ayah cards → reveal → self-grade fan-out
 * through the single scheduling authority (U7).
 */
class StudyViewModel(
    private val quranSource: suspend () -> QuranRepository,
    private val mistakes: MistakeRepository,
    private val policy: SchedulingPolicy,
    private val mnemonicRepo: MnemonicRepository? = null,
    private val enqueueGeneration: () -> Unit = {},
) : ViewModel() {

    /** Guards grade/suspend/delete against double taps (review finding, corroborated). */
    private var actionInFlight = false

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
            val quran = quranSource()
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

    fun reveal() {
        val card = cards.getOrNull(index) ?: return
        viewModelScope.launch {
            val mnemonics = fetchMnemonics(card)
            if (mnemonics.any { it.status == MnemonicStatus.PENDING }) enqueueGeneration()
            publish(revealed = true, mnemonics = mnemonics)
        }
    }

    fun saveMnemonic(entity: MnemonicEntity, text: String) {
        val card = cards.getOrNull(index) ?: return
        viewModelScope.launch {
            mnemonicRepo?.saveUserText(entity, text)
            publish(revealed = true, mnemonics = fetchMnemonics(card))
        }
    }

    fun retryMnemonic(entity: MnemonicEntity) {
        val card = cards.getOrNull(index) ?: return
        viewModelScope.launch {
            mnemonicRepo?.retryFailed(entity)
            enqueueGeneration()
            publish(revealed = true, mnemonics = fetchMnemonics(card))
        }
    }

    private suspend fun fetchMnemonics(card: StudyCard): List<MnemonicEntity> {
        val repo = mnemonicRepo ?: return emptyList()
        return card.mutashabihat.map { group ->
            val target = card.ayat.firstOrNull { it in group.members } ?: card.primaryAyah
            repo.mnemonicFor(group, target)
        }
    }

    /** One grade per card, fanned out to every contained spot (R19). */
    fun grade(grade: ReviewGrade) {
        val card = cards.getOrNull(index) ?: return
        if (actionInFlight) return
        actionInFlight = true
        viewModelScope.launch {
            try {
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
            } finally {
                actionInFlight = false
            }
        }
    }

    fun suspendSpot(ref: WordRef) {
        if (actionInFlight) return
        actionInFlight = true
        viewModelScope.launch {
            try {
                mistakes.suspend(ref)
                skipCurrentCard()
            } finally {
                actionInFlight = false
            }
        }
    }

    fun deleteSpot(ref: WordRef) {
        if (actionInFlight) return
        actionInFlight = true
        viewModelScope.launch {
            try {
                mistakes.delete(ref)
                skipCurrentCard()
            } finally {
                actionInFlight = false
            }
        }
    }

    private fun skipCurrentCard() {
        index++
        if (index >= cards.size) {
            _state.value = StudyUiState.SessionDone(graded)
        } else {
            publish(revealed = false)
        }
    }

    private fun publish(revealed: Boolean, mnemonics: List<MnemonicEntity> = emptyList()) {
        val card = cards.getOrNull(index) ?: return
        _state.value = StudyUiState.Reviewing(card, index + 1, cards.size, revealed, reviewAhead, mnemonics)
    }

    companion object {
        fun factory(app: TarteelApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StudyViewModel(
                    { app.quran().await() }, app.mistakes, app.policy, app.mnemonicRepo,
                    enqueueGeneration = { GenerationWorker.enqueue(app) },
                ) as T
        }
    }
}
