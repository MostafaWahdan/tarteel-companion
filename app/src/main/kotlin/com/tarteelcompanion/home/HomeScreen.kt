package com.tarteelcompanion.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tarteelcompanion.TarteelApp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HomeCounts(
    val dueStudy: Int,
    val pendingQuiz: Int,
    val lastImportDaysAgo: Long?,
    val hasAnySpots: Boolean,
)

/**
 * Home dashboard (U12): today's loop at a glance — due study, pending quiz, and how
 * long screenshots have gone unimported (the success criterion is that they don't
 * pile up). First launch points at Import (M4 empty states).
 */
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as TarteelApp

    val counts by produceState<HomeCounts?>(initialValue = null) {
        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        val due = app.policy.studyQueue(today, now).size
        val quiz = app.policy.quizQueue(today, now).size
        val lastImport = app.database.importDao().latest()?.importedAtEpochMillis?.let {
            val importDay = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            LocalDate.now().toEpochDay() - importDay.toEpochDay()
        }
        value = HomeCounts(due, quiz, lastImport, app.policy.hasActiveSpots())
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tarteel Companion", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        Spacer(Modifier.height(16.dp))

        val c = counts
        if (c == null) {
            Text("Loading…")
        } else if (!c.hasAnySpots && c.lastImportDaysAgo == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Start here", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Import your Tarteel screenshots to turn mistakes into a study plan.")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Due to study", "${c.dueStudy}", Modifier.weight(1f)) { onNavigate("study") }
                StatCard("Quiz pending", "${c.pendingQuiz}", Modifier.weight(1f)) { onNavigate("quiz") }
            }
            Spacer(Modifier.height(8.dp))
            StatCard(
                "Last import",
                when (c.lastImportDaysAgo) {
                    null -> "never"
                    0L -> "today"
                    1L -> "yesterday"
                    else -> "${c.lastImportDaysAgo} days ago"
                },
                Modifier.fillMaxWidth(),
            ) { onNavigate("import") }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier, onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
