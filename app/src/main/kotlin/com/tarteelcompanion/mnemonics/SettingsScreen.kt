package com.tarteelcompanion.mnemonics

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tarteelcompanion.TarteelApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BYO-key settings (R23/I10): the user's own Gemini key, stored encrypted on-device.
 * No key = no mnemonic generation; everything else works — stated right on the screen.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as TarteelApp
    val scope = rememberCoroutineScope()

    var keyInput by remember { mutableStateOf("") }
    var storedKeyHint by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val hasStoredKey = storedKeyHint != null

    fun hintOf(key: String) = "…${key.takeLast(4)}"

    LaunchedEffect(Unit) { storedKeyHint = app.apiKeyStore.load()?.let(::hintOf) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Mnemonic generation", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Mnemonics are drafted in Arabic by Google Gemini using your own API key " +
                "(free tier at aistudio.google.com). Without a key, mnemonic generation is " +
                "off — importing, studying, and quizzing all work fully offline. Only " +
                "canonical verse text is ever sent; never your screenshots or personal data.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            storedKeyHint?.let { "Key $it is saved on this device (encrypted)." } ?: "No key saved.",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                enabled = keyInput.isNotBlank(),
                onClick = {
                    scope.launch {
                        val key = keyInput.trim()
                        app.apiKeyStore.save(key)
                        keyInput = ""
                        storedKeyHint = hintOf(key)
                        status = "Key ${hintOf(key)} saved."
                        GenerationWorker.enqueue(context) // pick up any waiting mnemonics
                    }
                },
            ) { Text("Save key") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = hasStoredKey || keyInput.isNotBlank(),
                onClick = {
                    scope.launch {
                        // A typed key is saved first, then tested — testing the stored
                        // key while a fresh one sits unsaved in the field silently
                        // tested the OLD key (rotation confusion, user-reported).
                        if (keyInput.isNotBlank()) {
                            val key = keyInput.trim()
                            app.apiKeyStore.save(key)
                            keyInput = ""
                            storedKeyHint = hintOf(key)
                            GenerationWorker.enqueue(context)
                        }
                        val key = app.apiKeyStore.load()
                        status = if (key == null) {
                            "No key saved."
                        } else {
                            status = "Testing key ${hintOf(key)}…"
                            when (val r = withContext(Dispatchers.IO) { GeminiClient().generate(key, "قل: نعم") }) {
                                is LlmResult.Success -> "Key ${hintOf(key)}: connection OK."
                                is LlmResult.Retryable -> "Key ${hintOf(key)} temporarily unavailable: ${r.reason}"
                                is LlmResult.Failed -> "Key ${hintOf(key)} failed: ${r.reason}"
                            }
                        }
                    }
                },
            ) { Text("Test connection") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = hasStoredKey,
                onClick = {
                    scope.launch {
                        app.apiKeyStore.clear()
                        storedKeyHint = null
                        status = "Key removed."
                    }
                },
            ) { Text("Remove") }
        }
        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
