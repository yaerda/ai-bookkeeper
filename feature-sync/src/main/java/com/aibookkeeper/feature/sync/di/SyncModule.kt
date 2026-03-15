package com.aibookkeeper.feature.sync.di

import com.aibookkeeper.feature.sync.queue.NoOpSyncManager
import com.aibookkeeper.feature.sync.queue.SyncManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSyncManager(impl: NoOpSyncManager): SyncManager
}
