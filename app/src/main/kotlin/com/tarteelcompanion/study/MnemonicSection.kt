package com.tarteelcompanion.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tarteelcompanion.mnemonics.MnemonicEntity
import com.tarteelcompanion.mnemonics.MnemonicStatus

/**
 * Arabic mnemonics under the comparison (R12/R22): ready text is editable (user text
 * always wins); pending shows a quiet waiting note; failed shows the reason + retry.
 */
@Composable
fun MnemonicSection(
    mnemonics: List<MnemonicEntity>,
    onSave: (MnemonicEntity, String) -> Unit,
    onRetry: (MnemonicEntity) -> Unit,
) {
    var editing by remember { mutableStateOf<MnemonicEntity?>(null) }
    var editText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for (m in mnemonics) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    when (m.status) {
                        MnemonicStatus.READY -> {
                            Text(
                                m.text.orEmpty(),
                                fontSize = 18.sp,
                                lineHeight = 30.sp,
                                textAlign = TextAlign.Right,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row {
                                TextButton(onClick = {
                                    editing = m
                                    editText = m.text.orEmpty()
                                }) { Text("Edit") }
                            }
                        }
                        MnemonicStatus.PENDING -> {
                            Text(
                                "المُذكِّرة قيد الإنشاء…",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Right,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(onClick = {
                                editing = m
                                editText = ""
                            }) { Text("Write your own") }
                        }
                        MnemonicStatus.FAILED -> {
                            Text(
                                "Generation failed — ${m.failureReason ?: "check your key in Settings"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Row {
                                TextButton(onClick = { onRetry(m) }) { Text("Retry") }
                                TextButton(onClick = {
                                    editing = m
                                    editText = ""
                                }) { Text("Write your own") }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { entity ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Mnemonic") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editText.isNotBlank(),
                    onClick = {
                        onSave(entity, editText)
                        editing = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
