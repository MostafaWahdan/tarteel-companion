package com.tarteelcompanion.mnemonics

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Key access surface, abstracted so JVM tests can fake it (no Keystore in Robolectric). */
interface ApiKeyProvider {
    suspend fun save(apiKey: String)
    suspend fun load(): String?
    suspend fun clear()
}

/**
 * Stores the user's Gemini API key encrypted at rest: AES/GCM with a key that lives in
 * the Android Keystore (never exported), ciphertext in DataStore. The key file is also
 * excluded from Auto Backup via allowBackup=false (R23; security review finding 18).
 * The plaintext key must never be logged or exported.
 */
class ApiKeyStore(private val context: Context) : ApiKeyProvider {

    private val prefKey = stringPreferencesKey("gemini_api_key_ct")

    override suspend fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ct = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        context.settingsDataStore.edit { it[prefKey] = blob }
    }

    override suspend fun load(): String? {
        val blob = context.settingsDataStore.data.first()[prefKey] ?: return null
        return try {
            val (ivB64, ctB64) = blob.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null // corrupt blob or keystore reset: behave as "no key" rather than crash
        }
    }

    override suspend fun clear() {
        context.settingsDataStore.edit { it.remove(prefKey) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gemini-api-key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
