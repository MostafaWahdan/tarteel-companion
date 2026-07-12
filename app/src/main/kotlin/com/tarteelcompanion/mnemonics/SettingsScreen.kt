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
    var hasStoredKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { hasStoredKey = app.apiKeyStore.load() != null }

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
            if (hasStoredKey) "A key is saved on this device (encrypted)." else "No key saved.",
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
                        app.apiKeyStore.save(keyInput.trim())
                        keyInput = ""
                        hasStoredKey = true
                        status = "Key saved."
                        GenerationWorker.enqueue(context) // pick up any waiting mnemonics
                    }
                },
            ) { Text("Save key") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = hasStoredKey,
                onClick = {
                    scope.launch {
                        status = "Testing…"
                        val key = app.apiKeyStore.load()
                        status = if (key == null) {
                            "No key saved."
                        } else {
                            when (val r = withContext(Dispatchers.IO) { GeminiClient().generate(key, "قل: نعم") }) {
                                is LlmResult.Success -> "Connection OK."
                                is LlmResult.Retryable -> "Temporarily unavailable: ${r.reason}"
                                is LlmResult.Failed -> "Failed: ${r.reason}"
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
                        hasStoredKey = false
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
