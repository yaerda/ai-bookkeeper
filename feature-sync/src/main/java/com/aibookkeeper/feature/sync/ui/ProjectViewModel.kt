package com.aibookkeeper.feature.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.ProjectDraft
import com.aibookkeeper.core.data.model.ProjectScope
import com.aibookkeeper.core.data.model.ProjectStats
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerSelection
import com.aibookkeeper.core.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val ledgerContext: LedgerContext,
    private val projectRepository: ProjectRepository
) : ViewModel() {
    val ledgerState = ledgerContext.state
    val projectState = projectRepository.currentLedgerState
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()
    private val _selectedScope = MutableStateFlow<ProjectScope?>(null)
    val selectedScope: StateFlow<ProjectScope?> = _selectedScope.asStateFlow()
    private val _selectedStats = MutableStateFlow<ProjectStats?>(null)
    val selectedStats: StateFlow<ProjectStats?> = _selectedStats.asStateFlow()
    private var context = currentContext()
    private var epoch = 0L
    private var requestJob: Job? = null

    init {
        viewModelScope.launch {
            combine(ledgerState, projectState) { _, _ -> currentContext() }.collect {
                synchronizeContext()
            }
        }
    }

    fun clearMessage() { _message.value = null }

    fun refresh() = launchRequest("项目已刷新") { request ->
        projectRepository.refreshCurrentLedger()
        ensureCurrent(request)
        _selectedProjectId.value?.let { loadProjectDetails(it, request) }
    }

    fun selectLedger(ledgerId: String) {
        ledgerContext.selectLedger(ledgerId)
        synchronizeContext()
    }

    fun selectProject(projectId: String) {
        synchronizeContext()
        _selectedProjectId.value = projectId
        _selectedScope.value = null
        _selectedStats.value = null
        launchRequest { loadProjectDetails(projectId, it) }
    }

    fun createProject(
        name: String, ledgerIds: List<String>?, enabled: Boolean,
        startDate: String?, endDate: String?
    ) {
        val targets = ledgerIds?.toList()
        launchRequest("项目已创建") { request ->
            val scope = projectRepository.createProject(ProjectDraft(name, targets, enabled, startDate, endDate))
            ensureCurrent(request)
            val stats = projectRepository.loadProjectStats(scope.projectId)
            ensureCurrent(request)
            _selectedProjectId.value = scope.projectId
            _selectedScope.value = scope
            _selectedStats.value = stats
            projectRepository.refreshCurrentLedger()
        }
    }

    fun saveLedgerBinding(
        projectId: String, ledgerId: String, version: Long,
        enabled: Boolean, startDate: String?, endDate: String?
    ) = launchRequest("项目范围已更新") { request ->
        projectRepository.updateProjectBinding(projectId, ledgerId, version, enabled, startDate, endDate)
        ensureCurrent(request)
        loadProjectDetails(projectId, request)
        projectRepository.refreshCurrentLedger()
    }

    private suspend fun loadProjectDetails(projectId: String, request: Request) {
        val scope = projectRepository.loadProjectScope(projectId)
        ensureCurrent(request)
        val stats = projectRepository.loadProjectStats(projectId)
        ensureCurrent(request)
        if (_selectedProjectId.value != projectId) throw CancellationException("项目已切换")
        _selectedScope.value = scope
        _selectedStats.value = stats
    }

    private fun launchRequest(successMessage: String? = null, operation: suspend (Request) -> Unit) {
        synchronizeContext()
        requestJob?.cancel()
        val request = Request(context, ++epoch)
        _isLoading.value = true
        _message.value = null
        requestJob = viewModelScope.launch {
            try {
                operation(request)
                ensureCurrent(request)
                _message.value = successMessage
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(request)) _message.value = error.message ?: "操作失败"
            } finally {
                if (isCurrent(request)) _isLoading.value = false
            }
        }
    }

    private fun currentContext() = Context(
        ledgerState.value.selection, ledgerState.value.isSignedIn,
        projectState.value.accountId, projectState.value.contextVersion
    )

    private fun synchronizeContext() {
        val current = currentContext()
        if (current != context) {
            context = current
            epoch++
            requestJob?.cancel()
            requestJob = null
            _isLoading.value = false
            _message.value = null
            _selectedProjectId.value = null
            _selectedScope.value = null
            _selectedStats.value = null
        }
    }

    private fun isCurrent(request: Request): Boolean =
        request.context == currentContext() && request.epoch == epoch

    private fun ensureCurrent(request: Request) {
        if (!isCurrent(request)) throw CancellationException("账本或项目已切换")
    }

    private data class Context(
        val selection: LedgerSelection, val signedIn: Boolean,
        val accountId: String?, val projectContext: Long
    )
    private data class Request(val context: Context, val epoch: Long)
}
