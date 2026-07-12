package com.tarteelcompanion.importflow

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tarteelcompanion.data.Detection
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.quran.LineType
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.quran.WordRef
import com.tarteelcompanion.ui.markColor

/**
 * Confirm/correct surface (R5) doubling as the manual-tagging fallback (R6): the
 * canonical mushaf page with tappable words, the original screenshot as a toggleable
 * reference panel, and Save / Wrong page (change the page number) / Discard exits.
 *
 * Tap model (resolved from the plan's open question, simplest deterministic choice):
 * each tap cycles a word none → yellow → red → brown → none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmTagScreen(
    item: ImportItem,
    quran: QuranRepository,
    initialPage: Int?,
    initialDetections: List<Detection>,
    index: Int,
    total: Int,
    onSave: (page: Int?, detections: List<Detection>) -> Unit,
    onDiscard: () -> Unit,
    onAutoDetect: ((Int) -> Unit)? = null,
) {
    var pageInput by remember(initialPage) { mutableStateOf((initialPage ?: 1).toString()) }
    var showReference by remember { mutableStateOf(true) }
    val marks = remember(initialDetections) {
        mutableStateMapOf<WordRef, MistakeType>().apply {
            initialDetections.forEach { put(it.ref, it.type) }
        }
    }

    val pageNumber = pageInput.toIntOrNull()?.coerceIn(1, 604)
    val page = pageNumber?.let { quran.page(it) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Confirm screenshot $index of $total", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = pageInput,
                onValueChange = { pageInput = it.filter(Char::isDigit).take(3) },
                label = { Text("Mushaf page") },
                singleLine = true,
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (onAutoDetect != null) {
                TextButton(
                    enabled = pageNumber != null,
                    onClick = { pageNumber?.let(onAutoDetect) },
                ) { Text("Auto-detect") }
            }
            TextButton(onClick = { showReference = !showReference }) {
                Text(if (showReference) "Hide screenshot" else "Show screenshot")
            }
        }

        if (showReference) {
            Spacer(Modifier.height(8.dp))
            Image(
                bitmap = item.bitmap.asImageBitmap(),
                contentDescription = "Original Tarteel screenshot",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Tap words to mark mistakes — tap cycles yellow (pronunciation) → red (wrong word) → brown (needed prompt) → clear.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (page == null) {
            Text("Enter a page number (1–604).")
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column {
                    for (line in page.lines) {
                        when (line.type) {
                            LineType.SURAH_HEADER -> Text(
                                line.headerText.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                            LineType.BASMALA -> Text(
                                "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                fontSize = 20.sp,
                            )
                            LineType.TEXT -> FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (word in line.words) {
                                    val mark = marks[word.ref]
                                    Text(
                                        word.text,
                                        fontSize = 24.sp,
                                        lineHeight = 40.sp,
                                        color = mark?.markColor()
                                            ?: MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (mark != null) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .clickable {
                                                marks.cycle(word.ref)
                                            }
                                            .padding(vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = page != null,
                onClick = {
                    onSave(pageNumber, marks.map { (ref, type) -> Detection(ref, type) })
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (marks.isEmpty()) "Save (no mistakes)" else "Save ${marks.size} mistake(s)") }
            OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                Text("Discard — not a page")
            }
        }
    }
}

private fun MutableMap<WordRef, MistakeType>.cycle(ref: WordRef) {
    when (this[ref]) {
        null -> this[ref] = MistakeType.PRONUNCIATION
        MistakeType.PRONUNCIATION -> this[ref] = MistakeType.WRONG_WORD
        MistakeType.WRONG_WORD -> this[ref] = MistakeType.PROMPT_NEEDED
        MistakeType.PROMPT_NEEDED -> remove(ref)
    }
}
