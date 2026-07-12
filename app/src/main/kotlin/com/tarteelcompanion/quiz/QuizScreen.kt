package com.tarteelcompanion.quiz

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tarteelcompanion.TarteelApp
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.study.ComparisonSection
import com.tarteelcompanion.study.GradeBar
import com.tarteelcompanion.ui.Centered
import com.tarteelcompanion.ui.PassageText

@Composable
fun QuizScreen(
    quran: QuranRepository?,
    viewModel: QuizViewModel = viewModel(
        factory = QuizViewModel.factory(LocalContext.current.applicationContext as TarteelApp),
    ),
) {
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        QuizUiState.Loading -> Centered { CircularProgressIndicator() }

        QuizUiState.Empty -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing to quiz yet.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Spots you study become quizzable the next day — study first, then come back cold.",
                    textAlign = TextAlign.Center,
                )
            }
        }

        is QuizUiState.Summary -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Quiz complete", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("${s.passed} of ${s.total} passed cold recall.")
                if (s.passed < s.total) {
                    Spacer(Modifier.height(4.dp))
                    Text("${s.total - s.passed} returned to the study queue with aids restored.")
                }
            }
        }

        is QuizUiState.Question -> Question(s, quran, viewModel)
    }
}

@Composable
private fun Question(s: QuizUiState.Question, quran: QuranRepository?, viewModel: QuizViewModel) {
    val card = s.card
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Cold recall ${s.index} of ${s.total}", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(12.dp))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "${card.surahName} — ${card.primaryAyah.ayah}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                card.leadIn?.let { leadIn ->
                    Spacer(Modifier.height(12.dp))
                    PassageText(words = leadIn, marks = emptyMap(), dimUnmarked = true)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        when (s.phase) {
            QuizUiState.Question.Phase.FRONT -> {
                Button(onClick = viewModel::reveal, modifier = Modifier.fillMaxWidth()) {
                    Text("Reveal passage")
                }
            }

            QuizUiState.Question.Phase.REVEALED -> {
                // Passage only — no marks, no comparisons, no mnemonic (R16).
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column {
                        card.passage.forEach { (_, words) ->
                            PassageText(words = words, marks = emptyMap())
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                GradeBar(onGrade = viewModel::grade)
            }

            QuizUiState.Question.Phase.GRADED -> {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column {
                        val marks = card.spots.associate { it.ref to it.type }
                        card.passage.forEach { (_, words) ->
                            PassageText(words = words, marks = marks)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (quran != null && card.mutashabihat.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 12.dp))
                            ComparisonSection(card = card, quran = quran)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = viewModel::next, modifier = Modifier.fillMaxWidth()) {
                    Text("Next")
                }
            }
        }
    }
}

