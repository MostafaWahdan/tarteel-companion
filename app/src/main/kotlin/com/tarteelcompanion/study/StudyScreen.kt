package com.tarteelcompanion.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tarteelcompanion.TarteelApp
import com.tarteelcompanion.data.model.ReviewGrade
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.ui.Centered
import com.tarteelcompanion.ui.PassageText

@Composable
fun StudyScreen(
    quran: QuranRepository?,
    viewModel: StudyViewModel = viewModel(
        factory = StudyViewModel.factory(LocalContext.current.applicationContext as TarteelApp),
    ),
) {
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        StudyUiState.Loading -> Centered { CircularProgressIndicator() }

        is StudyUiState.Empty -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (s.hasAnyActiveSpots) "Nothing due today." else "No mistake spots yet.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (s.hasAnyActiveSpots) {
                        "You're caught up — review ahead if you like."
                    } else {
                        "Import Tarteel screenshots to build your study queue."
                    },
                    textAlign = TextAlign.Center,
                )
                if (s.hasAnyActiveSpots) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadQueue(ahead = true) }) { Text("Review ahead") }
                }
            }
        }

        is StudyUiState.SessionDone -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Session complete", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("${s.graded} spot(s) graded.")
            }
        }

        is StudyUiState.Reviewing -> CardView(s, quran, viewModel)
    }
}

@Composable
private fun CardView(s: StudyUiState.Reviewing, quran: QuranRepository?, viewModel: StudyViewModel) {
    val card = s.card
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("Card ${s.index} of ${s.total}")
                    if (s.reviewAhead) append(" · review ahead")
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Card actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Suspend spot") },
                        onClick = { menuOpen = false; viewModel.suspendSpot(card.spots.first().ref) },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete spot…") },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Card front (R18): surah + ayah reference and the preceding-ayah lead-in.
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
                    Text("…continues after:", style = MaterialTheme.typography.labelMedium)
                    PassageText(words = leadIn, marks = emptyMap(), dimUnmarked = true)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (!s.revealed) {
            Button(onClick = viewModel::reveal, modifier = Modifier.fillMaxWidth()) {
                Text("Reveal passage")
            }
        } else {
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
                        MnemonicSection(
                            mnemonics = s.mnemonics,
                            onSave = viewModel::saveMnemonic,
                            onRetry = viewModel::retryMnemonic,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            GradeBar(onGrade = viewModel::grade)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this spot?") },
            text = { Text("Its mistake history is removed permanently. Suspend keeps history but stops reviews.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSpot(card.spots.first().ref)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun GradeBar(onGrade: (ReviewGrade) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { onGrade(ReviewGrade.AGAIN) }, modifier = Modifier.weight(1f)) { Text("Again") }
        OutlinedButton(onClick = { onGrade(ReviewGrade.HARD) }, modifier = Modifier.weight(1f)) { Text("Hard") }
        Button(onClick = { onGrade(ReviewGrade.GOOD) }, modifier = Modifier.weight(1f)) { Text("Good") }
        OutlinedButton(onClick = { onGrade(ReviewGrade.EASY) }, modifier = Modifier.weight(1f)) { Text("Easy") }
    }
}

