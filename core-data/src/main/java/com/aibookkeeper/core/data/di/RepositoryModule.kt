package com.aibookkeeper.core.data.di

import com.aibookkeeper.core.data.repository.AiExtractionRepository
import com.aibookkeeper.core.data.repository.AiExtractionRepositoryImpl
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.CategoryRepositoryImpl
import com.aibookkeeper.core.data.repository.RawEventRepository
import com.aibookkeeper.core.data.repository.RawEventRepositoryImpl
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.TransactionRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTransactionRepository(
        impl: TransactionRepositoryImpl
    ): TransactionRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(
        impl: CategoryRepositoryImpl
    ): CategoryRepository

    @Binds
    @Singleton
    abstract fun bindAiExtractionRepository(
        impl: AiExtractionRepositoryImpl
    ): AiExtractionRepository

    @Binds
    @Singleton
    abstract fun bindRawEventRepository(
        impl: RawEventRepositoryImpl
    ): RawEventRepository
}
