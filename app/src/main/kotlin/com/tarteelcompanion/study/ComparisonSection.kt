package com.tarteelcompanion.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tarteelcompanion.quran.AyahRef
import com.tarteelcompanion.quran.QuranRepository

private const val MAX_MEMBERS_SHOWN = 6

/**
 * Similar-verse comparison (U9/R11): the card's primary ayah first, each confusable
 * ayah below it, divergent words highlighted via the diacritic-stripped diff.
 * Deterministic dataset content only — no LLM involvement here.
 */
@Composable
fun ComparisonSection(card: StudyCard, quran: QuranRepository) {
    Column(Modifier.fillMaxWidth()) {
        Text("Similar verses", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        for (group in card.mutashabihat) {
            val target = card.ayat.firstOrNull { it in group.members } ?: card.primaryAyah
            val others = group.members.filter { it != target }.take(MAX_MEMBERS_SHOWN)
            val targetWords = quran.wordsOf(target).map { it.text }

            for (other in others) {
                val otherWords = quran.wordsOf(other).map { it.text }
                val (targetDiff, otherDiff) = MutashabihatDiffer.diff(targetWords, otherWords)

                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        ComparisonAyah(label = target, words = targetDiff, primary = true)
                        Spacer(Modifier.height(8.dp))
                        ComparisonAyah(label = other, words = otherDiff, primary = false)
                    }
                }
            }
            if (group.members.size - 1 > MAX_MEMBERS_SHOWN) {
                Text(
                    "+${group.members.size - 1 - MAX_MEMBERS_SHOWN} more similar verses",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ComparisonAyah(label: AyahRef, words: List<MutashabihatDiffer.DiffWord>, primary: Boolean) {
    Column {
        Text(
            "$label",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            buildAnnotatedString {
                words.forEachIndexed { i, w ->
                    if (i > 0) append(' ')
                    if (w.divergent) {
                        pushStyle(SpanStyle(color = Color(0xFF1565C0), fontWeight = FontWeight.Bold))
                        append(w.text)
                        pop()
                    } else {
                        append(w.text)
                    }
                }
            },
            textAlign = TextAlign.Right,
            fontSize = 22.sp,
            lineHeight = 38.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
