package com.tarteelcompanion.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tarteelcompanion.TarteelApp
import com.tarteelcompanion.data.entity.SpotEntity
import com.tarteelcompanion.data.model.SpotState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Archive browser (U12/R20): every spot — active, graduated, suspended — grouped by
 * surah, with suspend/reactivate and a guarded delete (confirmation dialog, distinct
 * from non-destructive suspend).
 */
@Composable
fun ArchiveScreen() {
    val app = LocalContext.current.applicationContext as TarteelApp
    val scope = rememberCoroutineScope()
    val spots by app.database.spotDao().observeAll().collectAsState(initial = emptyList())
    var confirmDelete by remember { mutableStateOf<SpotEntity?>(null) }

    if (spots.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text("No spots yet.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Imported mistakes and their history appear here.")
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val bySurah = spots.groupBy { it.surah }
        bySurah.forEach { (surah, surahSpots) ->
            item(key = "header-$surah") {
                Text(
                    "Surah $surah",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(surahSpots, key = { it.id }) { spot ->
                SpotRow(
                    spot = spot,
                    onSuspend = { scope.launch { app.mistakes.suspend(spot.wordRef) } },
                    onReactivate = { scope.launch { app.mistakes.reactivate(spot.wordRef) } },
                    onDelete = { confirmDelete = spot },
                )
            }
        }
    }

    confirmDelete?.let { spot ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${spot.wordRef}?") },
            text = { Text("This permanently removes the spot and its whole mistake history. Suspend instead to keep history without reviews.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.mistakes.delete(spot.wordRef) }
                    confirmDelete = null
                }) { Text("Delete permanently") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SpotRow(
    spot: SpotEntity,
    onSuspend: () -> Unit,
    onReactivate: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${spot.wordRef}", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            AssistChip(
                onClick = { expanded = !expanded },
                label = {
                    Text(
                        when (spot.state) {
                            SpotState.ACTIVE -> "Active"
                            SpotState.GRADUATED -> "Graduated"
                            SpotState.SUSPENDED -> "Suspended"
                        },
                    )
                },
            )
        }
        if (expanded) {
            Row {
                when (spot.state) {
                    SpotState.ACTIVE -> TextButton(onClick = onSuspend) { Text("Suspend") }
                    SpotState.SUSPENDED, SpotState.GRADUATED ->
                        TextButton(onClick = onReactivate) { Text("Reactivate") }
                }
                TextButton(onClick = onDelete) { Text("Delete…") }
            }
        }
    }
}
