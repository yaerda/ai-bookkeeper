package com.aibookkeeper.feature.sync.queue

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.mapper.CategoryMapper
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.feature.sync.auth.AccessToken
import com.aibookkeeper.feature.sync.auth.AuthenticationRequiredException
import com.aibookkeeper.feature.sync.auth.TokenProvider
import com.aibookkeeper.feature.sync.ledger.toCategory
import com.aibookkeeper.feature.sync.ledger.toCreateRequest
import com.aibookkeeper.feature.sync.network.ImportCategoriesRequest
import com.aibookkeeper.feature.sync.network.SyncApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

@Singleton
class LedgerCategorySync @Inject constructor(
    private val categoryDao: CategoryDao,
    private val mapper: CategoryMapper,
    private val api: SyncApi,
    private val preferences: SyncPreferences,
    private val tokenProvider: TokenProvider
) {
    private val mutex = Mutex()

    suspend fun syncDefault(token: AccessToken): List<Category> = mutex.withLock {
        if (!preferences.bindAccount(token.accountId)) throw AccountMismatchException()
        val local = categoryDao.getAllOnce().map { mapper.toDomain(it).toCreateRequest() }
        val batches = local.chunked(200).ifEmpty { listOf(emptyList()) }
        for (batch in batches) {
            api.importCategories(
                authorization = "Bearer ${token.value}",
                request = ImportCategoriesRequest(batch)
            ).requireCatalogBody()
        }
        // GET also backfills categories from historical transactions on other devices.
        val catalog = api.categories("Bearer ${token.value}")
            .requireCatalogBody().categories.map { it.toCategory() }
        categoryDao.mergeCloudCatalog(catalog.map(mapper::toEntity))
        catalog
    }

    private suspend fun <T> Response<T>.requireCatalogBody(): T {
        if (code() == 401 || code() == 403) {
            tokenProvider.invalidate()
            throw AuthenticationRequiredException()
        }
        if (!isSuccessful) throw toSyncException("同步分类")
        return body() ?: throw RetryableSyncException("分类响应为空")
    }
}
