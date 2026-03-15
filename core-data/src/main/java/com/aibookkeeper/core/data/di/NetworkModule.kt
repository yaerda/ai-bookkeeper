package com.aibookkeeper.core.data.di

import com.aibookkeeper.core.data.ai.AzureOpenAiConfig
import com.aibookkeeper.core.data.network.AzureOpenAiService
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
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideAzureOpenAiConfig(
        @Named("azureOpenAiApiKey") apiKey: String,
        @Named("azureOpenAiEndpoint") endpoint: String,
        @Named("azureOpenAiDeployment") deployment: String
    ): AzureOpenAiConfig = AzureOpenAiConfig(apiKey, endpoint, deployment)

    @Provides
    @Singleton
    @Named("azureRetrofit")
    fun provideAzureRetrofit(
        config: AzureOpenAiConfig,
        okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        val baseUrl = config.endpoint.let { ep ->
            if (ep.isBlank()) "https://placeholder.openai.azure.com/"
            else if (ep.endsWith("/")) ep else "$ep/"
        }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
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
