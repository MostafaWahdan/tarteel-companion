package com.tarteelcompanion.mnemonics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * LLM error classes (plan U10 / adversarial finding 10): retryable conditions back off
 * and wait; non-retryable ones become a visible FAILED state with a manual retry.
 */
sealed interface LlmResult {
    data class Success(val text: String) : LlmResult

    /** Offline, 429, 5xx — the queue backs off and retries. */
    data class Retryable(val reason: String) : LlmResult

    /** Bad/revoked key (401/403/400-key), safety block, malformed response. */
    data class Failed(val reason: String) : LlmResult
}

interface LlmClient {
    /** Generates a short Arabic mnemonic. [apiKey] is passed per call and never stored here. */
    fun generate(apiKey: String, prompt: String): LlmResult
}

/**
 * Gemini Flash via plain REST (BYO key, R23). Only canonical verse text and references
 * are ever included in [generate] prompts — never screenshots or personal data (R13).
 */
class GeminiClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    // The "-latest" alias tracks whatever flash model the key's account tier can use.
    // Pinned models rot: gemini-2.5-flash 404s for accounts created after ~2026
    // ("no longer available to new users") even though it appears in the model list.
    private val model: String = "gemini-flash-latest",
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override fun generate(apiKey: String, prompt: String): LlmResult {
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                    },
                )
            }
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            return LlmResult.Retryable("network: ${e.message}")
        }

        response.use {
            val text = it.body?.string().orEmpty()
            return when {
                it.code == 429 || it.code >= 500 -> LlmResult.Retryable("HTTP ${it.code}")
                it.code == 401 || it.code == 403 -> LlmResult.Failed("API key rejected (HTTP ${it.code})")
                !it.isSuccessful -> LlmResult.Failed("HTTP ${it.code}")
                else -> parse(text)
            }
        }
    }

    private fun parse(body: String): LlmResult = try {
        val root = json.parseToJsonElement(body).jsonObject
        val blocked = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
        if (blocked != null) {
            LlmResult.Failed("blocked by safety filter: $blocked")
        } else {
            val text = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.trim()
            if (text.isNullOrEmpty()) {
                LlmResult.Failed("empty or malformed response")
            } else {
                LlmResult.Success(text)
            }
        }
    } catch (_: Exception) {
        LlmResult.Failed("unparseable response")
    }
}
