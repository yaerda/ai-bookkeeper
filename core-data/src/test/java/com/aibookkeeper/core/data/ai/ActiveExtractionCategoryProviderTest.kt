package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.core.data.repository.LedgerOption
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveExtractionCategoryProviderTest {
    private val repository = mockk<CategoryRepository>()
    private val context = mockk<LedgerContext>()
    private val shared = LedgerOption("shared", "共享", "", "EDITOR", "FAMILY", false)
    private val state = MutableStateFlow(
        LedgerContextState(isSignedIn = true, ledgers = listOf(shared), selectedLedgerId = shared.id)
    )
    private val category = Category(90, "家庭菜园", "🪴", "#AABBCC", TransactionType.EXPENSE)

    private fun provider(): ActiveExtractionCategoryProvider {
        every { context.state } returns state
        return ActiveExtractionCategoryProvider(repository, context)
    }

    @Test
    fun `interactive extraction excludes local and stale additional names`() = runTest {
        every { repository.observeAllCategories() } returns flowOf(listOf(category))

        val names = provider().getCategoryNames(listOf("本地私有", "  家庭菜园  "))

        assertEquals(listOf("家庭菜园"), names)
    }

    @Test
    fun `loading shared catalog cannot fall back to local or hardcoded AI options`() = runTest {
        state.value = state.value.copy(isLoading = true)

        val error = runCatching { provider().getCategoryNames(listOf("餐饮")) }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, error)
    }

    @Test
    fun `empty catalog cannot invoke hardcoded AI category fallback`() = runTest {
        every { repository.observeAllCategories() } returns flowOf(emptyList())

        val error = runCatching { provider().getCategoryNames(emptyList()) }.exceptionOrNull()

        assertInstanceOf(IllegalStateException::class.java, error)
    }

    @Test
    fun `same ledger with a new session generation invalidates suspended extraction lookup`() = runTest {
        val categories = MutableSharedFlow<List<Category>>()
        every { repository.observeAllCategories() } returns categories
        val provider = provider()
        val request = async { runCatching { provider.getCategoryNames(emptyList()) } }
        runCurrent()
        state.value = state.value.copy(selectionVersion = 1)
        categories.emit(listOf(category))

        assertInstanceOf(LedgerSelectionChangedException::class.java, request.await().exceptionOrNull())
    }

    @Test
    fun `background extraction provider still reads only local Room categories`() = runTest {
        val dao = mockk<CategoryDao>()
        coEvery { dao.getAllOnce() } returns listOf(
            CategoryEntity(1, "本地私有", "ic_other", "#607D8B", "EXPENSE")
        )

        assertEquals(
            listOf("本地私有"),
            ExtractionCategoryProvider(dao).getCategoryNames(emptyList())
        )
    }
}
