package com.aibookkeeper.feature.sync.queue

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.mapper.CategoryMapper
import com.aibookkeeper.feature.sync.auth.AccessToken
import com.aibookkeeper.feature.sync.auth.AuthenticationRequiredException
import com.aibookkeeper.feature.sync.auth.TokenProvider
import com.aibookkeeper.feature.sync.network.CategoriesResponse
import com.aibookkeeper.feature.sync.network.ImportCategoriesRequest
import com.aibookkeeper.feature.sync.network.LedgerCategoryDto
import com.aibookkeeper.feature.sync.network.SyncApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

class LedgerCategorySyncTest {
    private val dao = mockk<CategoryDao>(relaxUnitFun = true)
    private val api = mockk<SyncApi>()
    private val preferences = mockk<SyncPreferences>()
    private val tokens = mockk<TokenProvider>(relaxUnitFun = true)
    private val sync = LedgerCategorySync(dao, CategoryMapper(), api, preferences, tokens)
    private val token = AccessToken("token", "owner-account")
    private val food = CategoryEntity(1, "餐饮", "ic_food", "#FF5722", "EXPENSE", sortOrder = 1)
    private val cloud = LedgerCategoryDto(900, "Web菜园", "EXPENSE", "🪴", "#123ABC", 42, false)

    @BeforeEach
    fun setup() {
        every { preferences.bindAccount(token.accountId) } returns true
        coEvery { api.importCategories(any(), any(), null) } returns Response.success(CategoriesResponse(emptyList()))
        coEvery { api.categories(any(), null) } returns Response.success(CategoriesResponse(listOf(cloud)))
    }

    @Test
    fun `imports default and unused custom categories only into owner default then reconciles cloud metadata`() = runTest {
        val salary = food.copy(id = 11, name = "工资", type = "INCOME", icon = "ic_salary", color = "#4CAF50")
        val unused = food.copy(id = 51, name = "  宠物   食品 ", icon = "🐈", isSystem = false)
        coEvery { dao.getAllOnce() } returns listOf(food, salary, unused)
        val request = slot<ImportCategoriesRequest>()
        coEvery { api.importCategories(any(), capture(request), null) } returns Response.success(
            CategoriesResponse(listOf(cloud))
        )

        val result = sync.syncDefault(token)

        assertEquals(listOf("餐饮", "工资", "宠物 食品"), request.captured.categories.map { it.name })
        assertEquals(listOf("ic_food", "ic_salary", "🐈"), request.captured.categories.map { it.icon })
        assertEquals(listOf("EXPENSE", "INCOME", "EXPENSE"), request.captured.categories.map { it.type })
        assertEquals("🪴", result.single().icon)
        assertEquals("#123ABC", result.single().color)
        coVerifyOrder {
            dao.getAllOnce()
            api.importCategories(any(), any(), null)
            api.categories(any(), null)
            dao.mergeCloudCatalog(match {
                it.single().id == 900L && it.single().name == "Web菜园" &&
                    it.single().icon == "🪴" && it.single().sortOrder == 42
            })
        }
    }

    @Test
    fun `category-only first sync imports every category in bounded batches`() = runTest {
        coEvery { dao.getAllOnce() } returns (1..417).map {
            food.copy(id = it.toLong(), name = "未使用分类$it", isSystem = false)
        }
        val requests = mutableListOf<ImportCategoriesRequest>()
        coEvery { api.importCategories(any(), capture(requests), null) } returns Response.success(
            CategoriesResponse(listOf(cloud))
        )

        sync.syncDefault(token)

        assertEquals(listOf(200, 200, 17), requests.map { it.categories.size })
        assertEquals(417, requests.flatMap { it.categories }.map { it.name }.distinct().size)
        coVerify(exactly = 1) { api.categories(any(), null) }
    }

    @Test
    fun `empty local catalog still downloads defaults and unused Web categories`() = runTest {
        coEvery { dao.getAllOnce() } returns emptyList()

        assertEquals("Web菜园", sync.syncDefault(token).single().name)

        coVerify { api.importCategories(any(), ImportCategoriesRequest(emptyList()), null) }
        coVerify { dao.mergeCloudCatalog(match { it.single().name == "Web菜园" }) }
    }

    @Test
    fun `account mismatch stops before reading or importing local categories`() = runTest {
        every { preferences.bindAccount(token.accountId) } returns false

        val failure = runCatching { sync.syncDefault(token) }.exceptionOrNull()

        assertInstanceOf(AccountMismatchException::class.java, failure)
        coVerify(exactly = 0) { dao.getAllOnce() }
        coVerify(exactly = 0) { api.importCategories(any(), any(), any()) }
        coVerify(exactly = 0) { dao.mergeCloudCatalog(any()) }
    }

    @Test
    fun `failed category import does not replace any local data`() = runTest {
        coEvery { dao.getAllOnce() } returns listOf(food)
        coEvery { api.importCategories(any(), any(), null) } returns Response.error(
            503, "{}".toResponseBody()
        )

        val failure = runCatching { sync.syncDefault(token) }.exceptionOrNull()

        assertInstanceOf(RetryableSyncException::class.java, failure)
        coVerify(exactly = 0) { dao.mergeCloudCatalog(any()) }
    }

    @Test
    fun `category permission failure reports authentication required rather than continuing transaction sync`() = runTest {
        coEvery { dao.getAllOnce() } returns listOf(food)
        coEvery { api.categories(any(), null) } returns Response.error(403, "{}".toResponseBody())

        val failure = runCatching { sync.syncDefault(token) }.exceptionOrNull()

        assertInstanceOf(AuthenticationRequiredException::class.java, failure)
        coVerify { tokens.invalidate() }
        coVerify(exactly = 0) { dao.mergeCloudCatalog(any()) }
    }

    @Test
    fun `unsafe cloud category IDs fail before local reconciliation`() = runTest {
        coEvery { dao.getAllOnce() } returns emptyList()
        coEvery { api.categories(any(), null) } returns Response.success(
            CategoriesResponse(listOf(cloud.copy(id = Long.MAX_VALUE)))
        )

        assertTrue(runCatching { sync.syncDefault(token) }.isFailure)
        coVerify(exactly = 0) { dao.mergeCloudCatalog(any()) }
    }
}
