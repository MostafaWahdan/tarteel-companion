package com.tarteelcompanion.importflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tarteelcompanion.TarteelApp
import com.tarteelcompanion.extraction.AnchoringPipeline
import com.tarteelcompanion.extraction.ExtractionResult
import com.tarteelcompanion.quran.QuranRepository
import com.tarteelcompanion.ui.Centered

/** F1 entry: multi-select picker (share-sheet intents land here too via TarteelApp). */
@Composable
fun ImportScreen(
    quran: QuranRepository?,
    viewModel: ImportViewModel = viewModel(
        factory = run {
            val app = LocalContext.current.applicationContext as TarteelApp
            ImportViewModel.factory(app) { AnchoringPipeline(app.quran.await()) }
        },
    ),
) {
    val app = LocalContext.current.applicationContext as TarteelApp
    val state by viewModel.state.collectAsState()
    val pendingShares by app.pendingShares.collectAsState()

    // Share-sheet arrivals queue behind any active batch (I4).
    LaunchedEffect(pendingShares) {
        if (pendingShares.isNotEmpty()) {
            viewModel.enqueue(pendingShares)
            app.pendingShares.value = emptyList()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.enqueue(uris) }

    when (val s = state) {
        ImportUiState.Idle -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Import Tarteel screenshots", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pick session screenshots with marked mistakes, or share them straight from your gallery.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Pick screenshots") }
            }
        }

        is ImportUiState.Processing -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Processing ${s.current} of ${s.total}…")
            }
        }

        is ImportUiState.Confirming -> {
            if (quran == null) {
                Centered { CircularProgressIndicator() }
            } else {
                val extraction = s.item.extraction
                val (page, detections) = when (extraction) {
                    is ExtractionResult.Extracted -> extraction.pageNumber to extraction.detections
                    is ExtractionResult.NeedsManual -> null to emptyList()
                }
                ConfirmTagScreen(
                    item = s.item,
                    quran = quran,
                    initialPage = page,
                    initialDetections = detections,
                    index = s.index,
                    total = s.total,
                    onSave = { p, d -> viewModel.save(s.item, p, d) },
                    onDiscard = viewModel::discard,
                    onAutoDetect = viewModel::autoDetect,
                )
            }
        }

        is ImportUiState.BatchDone -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Import complete", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("${s.saved} saved")
                        if (s.duplicates > 0) append(", ${s.duplicates} duplicate(s) skipped")
                        if (s.discarded > 0) append(", ${s.discarded} discarded")
                    },
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Import more") }
            }
        }

        is ImportUiState.Error -> Centered { Text(s.message) }
    }
}

