package com.aibookkeeper.core.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for sensitive configuration values (API keys, tokens)
 * using EncryptedSharedPreferences backed by Android Keystore.
 */
@Singleton
class SecureConfigStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val FILE_NAME = "ai_bookkeeper_secure_prefs"
        private const val KEY_AZURE_API_KEY = "azure_openai_api_key"
        private const val KEY_AZURE_ENDPOINT = "azure_openai_endpoint"
        private const val KEY_AZURE_DEPLOYMENT = "azure_openai_deployment"
        private const val KEY_AZURE_SPEECH_DEPLOYMENT = "azure_openai_speech_deployment"
        private const val KEY_AZURE_TEXT_PROMPT = "azure_openai_text_prompt"
        private const val KEY_PREFER_LOCAL_SPEECH = "prefer_local_speech"
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(): String = prefs.getString(KEY_AZURE_API_KEY, "") ?: ""

    fun setApiKey(apiKey: String) {
        prefs.edit().putString(KEY_AZURE_API_KEY, apiKey).apply()
    }

    fun getEndpoint(): String = prefs.getString(KEY_AZURE_ENDPOINT, "") ?: ""

    fun setEndpoint(endpoint: String) {
        prefs.edit().putString(KEY_AZURE_ENDPOINT, endpoint).apply()
    }

    fun getDeployment(): String = prefs.getString(KEY_AZURE_DEPLOYMENT, "") ?: ""

    fun setDeployment(deployment: String) {
        prefs.edit().putString(KEY_AZURE_DEPLOYMENT, deployment).apply()
    }

    fun getSpeechDeployment(): String = prefs.getString(KEY_AZURE_SPEECH_DEPLOYMENT, "") ?: ""

    fun setSpeechDeployment(deployment: String) {
        prefs.edit().putString(KEY_AZURE_SPEECH_DEPLOYMENT, deployment).apply()
    }

    fun getTextPrompt(): String = prefs.getString(KEY_AZURE_TEXT_PROMPT, "") ?: ""

    fun setTextPrompt(prompt: String) {
        prefs.edit().putString(KEY_AZURE_TEXT_PROMPT, prompt).apply()
    }

    fun isLocalSpeechPreferred(): Boolean = prefs.getBoolean(KEY_PREFER_LOCAL_SPEECH, true)

    fun setLocalSpeechPreferred(preferLocalSpeech: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_LOCAL_SPEECH, preferLocalSpeech).apply()
    }

    /**
     * Seed encrypted storage from BuildConfig for local/dev builds.
     * Existing user edits in Settings always win over build-time defaults.
     */
    fun migrateFromBuildConfig(
        apiKey: String,
        endpoint: String,
        deployment: String,
        speechDeployment: String = ""
    ) {
        if (getApiKey().isBlank() && apiKey.isNotBlank()) setApiKey(apiKey)
        if (getEndpoint().isBlank() && endpoint.isNotBlank()) setEndpoint(endpoint)
        if (getDeployment().isBlank() && deployment.isNotBlank()) setDeployment(deployment)
        if (getSpeechDeployment().isBlank() && speechDeployment.isNotBlank()) {
            setSpeechDeployment(speechDeployment)
        }
    }

    fun hasValidConfig(): Boolean = getApiKey().isNotBlank() && getEndpoint().isNotBlank()

    fun hasValidSpeechConfig(): Boolean =
        hasValidConfig() && getSpeechDeployment().isNotBlank()
}
