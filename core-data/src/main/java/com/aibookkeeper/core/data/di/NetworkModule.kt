package com.aibookkeeper.core.data.di

import com.aibookkeeper.core.data.ai.AzureOpenAiConfig
import com.aibookkeeper.core.data.network.AzureOpenAiService
import com.aibookkeeper.core.data.security.SecureConfigStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()
    }

    /**
     * Provides Azure config preferring EncryptedSharedPreferences,
     * falling back to BuildConfig values passed via @Named strings.
     */
    @Provides
    @Singleton
    fun provideAzureOpenAiConfig(
        secureStore: SecureConfigStore,
        @Named("azureOpenAiApiKey") buildConfigApiKey: String,
        @Named("azureOpenAiEndpoint") buildConfigEndpoint: String,
        @Named("azureOpenAiDeployment") buildConfigDeployment: String,
        @Named("azureOpenAiSpeechDeployment") buildConfigSpeechDeployment: String
    ): AzureOpenAiConfig {
        // Migrate BuildConfig values to encrypted storage on first run
        secureStore.migrateFromBuildConfig(
            buildConfigApiKey,
            buildConfigEndpoint,
            buildConfigDeployment,
            buildConfigSpeechDeployment
        )

        // Always read from encrypted storage
        return AzureOpenAiConfig(
            apiKey = secureStore.getApiKey().ifBlank { buildConfigApiKey },
            endpoint = secureStore.getEndpoint().ifBlank { buildConfigEndpoint },
            deployment = secureStore.getDeployment().ifBlank { buildConfigDeployment }
        )
    }

    @Provides
    @Singleton
    @Named("azureRetrofit")
    fun provideAzureRetrofit(
        okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://placeholder.openai.azure.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideAzureOpenAiService(
        @Named("azureRetrofit") retrofit: Retrofit
    ): AzureOpenAiService = retrofit.create(AzureOpenAiService::class.java)
}
