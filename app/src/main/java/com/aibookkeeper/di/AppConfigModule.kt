package com.aibookkeeper.di

import com.aibookkeeper.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/**
 * Provides Azure OpenAI configuration strings from BuildConfig
 * (sourced from local.properties at build time).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {

    @Provides
    @Named("azureOpenAiApiKey")
    fun provideAzureOpenAiApiKey(): String = BuildConfig.AZURE_OPENAI_API_KEY

    @Provides
    @Named("azureOpenAiEndpoint")
    fun provideAzureOpenAiEndpoint(): String = BuildConfig.AZURE_OPENAI_ENDPOINT

    @Provides
    @Named("azureOpenAiDeployment")
    fun provideAzureOpenAiDeployment(): String = BuildConfig.AZURE_OPENAI_DEPLOYMENT

    @Provides
    @Named("azureOpenAiSpeechDeployment")
    fun provideAzureOpenAiSpeechDeployment(): String = BuildConfig.AZURE_OPENAI_SPEECH_DEPLOYMENT
}
