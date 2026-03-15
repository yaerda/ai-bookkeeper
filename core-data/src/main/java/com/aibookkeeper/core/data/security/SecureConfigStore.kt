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

    /**
     * Migrate BuildConfig values into encrypted storage on first launch.
     * Only writes if encrypted store is empty (preserves user overrides).
     */
    fun migrateFromBuildConfig(apiKey: String, endpoint: String, deployment: String) {
        if (getApiKey().isBlank() && apiKey.isNotBlank()) setApiKey(apiKey)
        if (getEndpoint().isBlank() && endpoint.isNotBlank()) setEndpoint(endpoint)
        if (getDeployment().isBlank() && deployment.isNotBlank()) setDeployment(deployment)
    }

    fun hasValidConfig(): Boolean = getApiKey().isNotBlank() && getEndpoint().isNotBlank()
}
