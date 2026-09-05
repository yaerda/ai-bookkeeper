package com.aibookkeeper.feature.sync.di

import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.TokenProvider
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.feature.sync.ledger.ActiveLedgerCategoryRepository
import com.aibookkeeper.feature.sync.ledger.ActiveLedgerTransactionRepository
import com.aibookkeeper.feature.sync.ledger.SharedLedgerSession
import com.aibookkeeper.feature.sync.queue.CloudSyncManager
import com.aibookkeeper.feature.sync.queue.SyncManager
import com.aibookkeeper.feature.sync.network.SyncApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSyncManager(impl: CloudSyncManager): SyncManager

    @Binds
    @Singleton
    abstract fun bindTokenProvider(impl: AuthManager): TokenProvider

    @Binds
    @Singleton
    abstract fun bindLedgerContext(impl: SharedLedgerSession): LedgerContext

    @Binds
    @Singleton
    abstract fun bindTransactionRepository(
        impl: ActiveLedgerTransactionRepository
    ): TransactionRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(
        impl: ActiveLedgerCategoryRepository
    ): CategoryRepository

    companion object {
        @Provides
        @Singleton
        @Named("syncRetrofit")
        fun provideSyncRetrofit(
            okHttpClient: OkHttpClient,
            json: Json
        ): Retrofit = Retrofit.Builder()
            .baseUrl("https://aibookkeeper-sync-prod-yaerda.azurewebsites.net/api/")
            .client(okHttpClient)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType())
            )
            .build()

        @Provides
        @Singleton
        fun provideSyncApi(
            @Named("syncRetrofit") retrofit: Retrofit
        ): SyncApi = retrofit.create(SyncApi::class.java)
    }
}
