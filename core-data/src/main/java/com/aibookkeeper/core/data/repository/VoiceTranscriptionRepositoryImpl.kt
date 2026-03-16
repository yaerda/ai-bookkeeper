package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.network.AzureOpenAiService
import com.aibookkeeper.core.data.security.SecureConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Named

class VoiceTranscriptionRepositoryImpl @Inject constructor(
    private val service: AzureOpenAiService,
    private val secureStore: SecureConfigStore,
    @Named("azureOpenAiApiKey") private val buildConfigApiKey: String,
    @Named("azureOpenAiEndpoint") private val buildConfigEndpoint: String,
    @Named("azureOpenAiSpeechDeployment") private val buildConfigSpeechDeployment: String
) : VoiceTranscriptionRepository {

    override fun isConfigured(): Boolean = currentConfig().isConfigured

    override suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val config = currentConfig()
            require(config.isConfigured) { "请先在设置中配置 Azure 语音 Deployment" }
            require(audioFile.exists() && audioFile.length() > 0L) { "录音文件为空" }

            val filePart = MultipartBody.Part.createFormData(
                name = "file",
                filename = audioFile.name,
                body = audioFile.asRequestBody("audio/mp4".toMediaType())
            )
            val responseFormat = "json".toRequestBody("text/plain".toMediaType())
            val language = "zh".toRequestBody("text/plain".toMediaType())
            val model = config.deployment.toRequestBody("text/plain".toMediaType())

            val attempts = listOf(
                "2025-03-01-preview" to model,
                "2025-04-15-preview" to model,
                "2024-06-01" to null
            )

            var lastError: Throwable? = null
            for ((apiVersion, modelBody) in attempts) {
                try {
                    val response = service.transcribe(
                        url = buildTranscriptionUrl(config.endpoint, config.deployment, apiVersion),
                        apiKey = config.apiKey,
                        file = filePart,
                        responseFormat = responseFormat,
                        language = language,
                        model = modelBody
                    )
                    val text = response.text
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: response.combinedPhrases
                            ?.joinToString(separator = "\n") { it.text.trim() }
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }

                    if (!text.isNullOrBlank()) {
                        return@runCatching text
                    }
                } catch (error: Throwable) {
                    lastError = error
                }
            }

            throw lastError ?: IllegalStateException("Azure 语音识别失败")
        }.also {
            audioFile.delete()
        }
    }

    private fun currentConfig(): AzureSpeechConfig {
        return AzureSpeechConfig(
            apiKey = secureStore.getApiKey().ifBlank { buildConfigApiKey },
            endpoint = secureStore.getEndpoint().ifBlank { buildConfigEndpoint },
            deployment = secureStore.getSpeechDeployment().ifBlank { buildConfigSpeechDeployment }
        )
    }

    private fun buildTranscriptionUrl(endpoint: String, deployment: String, apiVersion: String): String {
        val normalizedEndpoint = endpoint
            .trim()
            .substringBefore("/openai/")
            .trimEnd('/')
        return "$normalizedEndpoint/openai/deployments/$deployment/audio/transcriptions?api-version=$apiVersion"
    }
}

private data class AzureSpeechConfig(
    val apiKey: String,
    val endpoint: String,
    val deployment: String
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && endpoint.isNotBlank() && deployment.isNotBlank()
}
