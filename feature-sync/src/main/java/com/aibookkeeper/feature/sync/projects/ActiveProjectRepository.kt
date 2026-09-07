package com.aibookkeeper.feature.sync.projects

import com.aibookkeeper.core.data.model.ProjectBinding
import com.aibookkeeper.core.data.model.ProjectDefaultsAvailability
import com.aibookkeeper.core.data.model.ProjectDraft
import com.aibookkeeper.core.data.model.ProjectLedgerState
import com.aibookkeeper.core.data.model.ProjectScope
import com.aibookkeeper.core.data.model.ProjectStats
import com.aibookkeeper.core.data.repository.LOCAL_LEDGER_ID
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerOption
import com.aibookkeeper.core.data.repository.LedgerSelection
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import com.aibookkeeper.core.data.repository.ProjectRepository
import com.aibookkeeper.core.data.repository.ProjectWriteDestination
import com.aibookkeeper.feature.sync.auth.AccessToken
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.network.CreateProjectRequest
import com.aibookkeeper.feature.sync.network.ProjectBindingDto
import com.aibookkeeper.feature.sync.network.ProjectLedgerUpdateRequest
import com.aibookkeeper.feature.sync.network.ProjectScopeResponse
import com.aibookkeeper.feature.sync.network.SyncApi
import com.aibookkeeper.feature.sync.queue.SyncPreferences
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class ActiveProjectRepository internal constructor(
    private val authManager: AuthManager,
    private val ledgerContext: LedgerContext,
    private val api: SyncApi,
    private val preferences: SyncPreferences,
    private val json: Json,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now
) : ProjectRepository {
    @Inject
    constructor(
        authManager: AuthManager,
        ledgerContext: LedgerContext,
        api: SyncApi,
        preferences: SyncPreferences,
        json: Json
    ) : this(authManager, ledgerContext, api, preferences, json,
        CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val lock = Any()
    private var context: Context? = null
    private var contextVersion = 0L
    private var nextRequest = 0L
    private val requests = mutableMapOf<String, Long>()
    // Scope responses are partial: they may advance versions but never establish defaults.
    private val scopeBindings = mutableMapOf<Pair<String, String>, ProjectBindingDto>()
    private val _currentLedgerState = MutableStateFlow(ProjectLedgerState())
    override val currentLedgerState = _currentLedgerState.asStateFlow()
    private val _defaultLedgerState = MutableStateFlow(ProjectLedgerState())
    override val defaultLedgerState: StateFlow<ProjectLedgerState> = _defaultLedgerState.asStateFlow()

    init {
        scope.launch {
            combine(authManager.state, ledgerContext.state, preferences.boundAccountId) { _, _, _ -> Unit }
                .collectLatest {
                    val pending = synchronized(lock) {
                        val current = synchronizeContext()
                        listOfNotNull(current.selectedLedgerId(), current.defaultLedgerId())
                            .distinct().map { begin("ledger:$it", it) }
                    }
                    coroutineScope {
                        pending.forEach { request ->
                            launch {
                                try {
                                    fetchLedger(request)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    // fetchLedger publishes only errors belonging to its current request.
                                }
                            }
                        }
                    }
                }
        }
    }

    override suspend fun refreshCurrentLedger() {
        val request = synchronized(lock) {
            val current = synchronizeContext()
            current.selectedLedgerId()?.let { begin("ledger:$it", it) }
        } ?: return
        fetchLedger(request)
    }

    override fun captureDestination(defaultRoom: Boolean): ProjectWriteDestination = synchronized(lock) {
        var current = synchronizeContext()
        val local = defaultRoom || current.selectedOption()?.isLocal == true
        // Project-tagged Room writes must never be claimed later by another sync account.
        if (local && current.defaultLedgerId() != null && current.boundAccountId == null) {
            check(preferences.bindAccount(requireNotNull(current.accountId)))
            current = synchronizeContext()
        }
        ProjectWriteDestination(
            accountId = current.accountId,
            ledgerId = if (local) current.defaultLedgerId() else current.selectedLedgerId(),
            defaultRoom = local,
            contextVersion = contextVersion,
            selection = current.selection,
            canWrite = if (local) {
                current.accountId == null || (current.isSignedIn &&
                    (current.boundAccountId == null || current.boundAccountId == current.accountId))
            } else current.selectedLedgerId() != null && current.selectedOption()?.canEdit == true
        )
    }

    override fun requireCurrentDestination(destination: ProjectWriteDestination) = synchronized(lock) {
        val current = synchronizeContext()
        val ledgerId = if (destination.defaultRoom) current.defaultLedgerId() else current.selectedLedgerId()
        if (destination.contextVersion != contextVersion || destination.accountId != current.accountId ||
            destination.ledgerId != ledgerId || destination.selection != current.selection
        ) throw LedgerSelectionChangedException()
    }

    override fun resolveProjectIdsForNewTransaction(): List<String>? =
        resolveProjectIds(captureDestination(), null)

    override fun resolveProjectIds(
        destination: ProjectWriteDestination,
        explicitProjectIds: List<String>?
    ): List<String>? = synchronized(lock) {
        requireCurrentDestination(destination)
        val current = requireNotNull(context)
        val state = if (destination.defaultRoom) _defaultLedgerState.value else _currentLedgerState.value
        if (explicitProjectIds != null) {
            require(explicitProjectIds.size <= 100 && explicitProjectIds.distinct().size == explicitProjectIds.size) {
                "项目选择无效"
            }
            if (explicitProjectIds.isNotEmpty()) {
                check(destination.ledgerId != null && state.accountId == destination.accountId &&
                    state.ledgerId == destination.ledgerId && state.canEdit &&
                    state.availability != ProjectDefaultsAvailability.UNAVAILABLE &&
                    (!destination.defaultRoom || current.boundAccountId == destination.accountId)
                ) { "当前本地账本的项目不可用，请重新选择" }
                check(explicitProjectIds.all { id -> state.projects.any { it.projectId == id } }) {
                    "项目不属于目标账本，请重新选择"
                }
            }
            explicitProjectIds.toList()
        } else if (state.accountId == destination.accountId && state.ledgerId == destination.ledgerId &&
            (!destination.defaultRoom || current.boundAccountId == destination.accountId)
        ) {
            state.defaultProjectIdsAt(now())
        } else null
    }

    override suspend fun createProject(draft: ProjectDraft): ProjectScope {
        val request = synchronized(lock) { begin("create") }
        val writable = request.context.ledgers.filter { it.canEdit && it.id != LOCAL_LEDGER_ID }.map { it.id }
        val targets = draft.ledgerIds?.toList() ?: writable
        require(targets.size in 1..100 && targets.distinct().size == targets.size &&
            targets.all { it in writable }) { "请选择 1 到 100 个可编辑账本" }
        require(draft.name.trim().isNotEmpty()) { "请输入项目名称" }
        validateDates(draft.startDate, draft.endDate)
        val token = token(request)
        val response = api.createProject(authorization(token), CreateProjectRequest(
            name = draft.name.trim(), ledgerIds = targets, enabled = draft.enabled,
            startDate = draft.startDate, endDate = draft.endDate
        ))
        return synchronized(lock) {
            ensureCurrent(request)
            if (!response.isSuccessful) throw projectError(response.code())
            val body = requireNotNull(response.body())
            check(body.ledgers.map { it.ledgerId }.toSet() == targets.toSet()) { "项目账本响应不匹配" }
            acceptScope(request, body).toModel()
        }
    }

    override suspend fun loadProjectScope(projectId: String): ProjectScope {
        val request = synchronized(lock) { begin("scope:$projectId") }
        val token = token(request)
        val response = api.projectScope(authorization(token), projectId)
        return synchronized(lock) {
            ensureCurrent(request)
            if (!response.isSuccessful) throw projectError(response.code())
            val body = requireNotNull(response.body())
            check(body.projectId == projectId) { "项目响应不匹配" }
            acceptScope(request, body).toModel()
        }
    }

    override suspend fun updateProjectBinding(
        projectId: String, ledgerId: String, version: Long,
        enabled: Boolean, startDate: String?, endDate: String?
    ): ProjectBinding {
        val request = synchronized(lock) { begin("binding:$projectId:$ledgerId", ledgerId) }
        check(request.context.ledgers.any { it.id == ledgerId && it.canEdit }) { "没有项目管理权限" }
        require(version >= 0)
        validateDates(startDate, endDate)
        val token = token(request)
        val response = api.updateProjectBinding(
            authorization(token), projectId, ledgerId,
            ProjectLedgerUpdateRequest(version, enabled, startDate, endDate)
        )
        return synchronized(lock) {
            ensureCurrent(request)
            if (!response.isSuccessful) throw projectError(response.code())
            val body = requireNotNull(response.body())
            check(body.projectId == projectId && body.ledgerId == ledgerId && body.version > version) {
                "项目版本响应不匹配"
            }
            validateBinding(request.context, body)
            mergeBinding(request.context, body).also { publishCachedStates(request.context) }.toModel()
        }
    }

    override suspend fun loadProjectStats(projectId: String, ledgerId: String?): ProjectStats {
        val request = synchronized(lock) { begin("stats:$projectId:$ledgerId") }
        if (ledgerId != null) check(request.context.ledgers.any { it.id == ledgerId })
        val token = token(request)
        val response = api.projectStats(authorization(token), projectId, ledgerId)
        return synchronized(lock) {
            ensureCurrent(request)
            if (!response.isSuccessful) throw projectError(response.code())
            requireNotNull(response.body()).also { body ->
                check(body.projectId == projectId && body.ledgers.all { item ->
                    (ledgerId == null || item.ledgerId == ledgerId) &&
                        request.context.ledgers.any { it.id == item.ledgerId }
                }) { "项目统计响应不匹配" }
            }.toModel()
        }
    }

    private suspend fun fetchLedger(request: Request) {
        synchronized(lock) {
            ensureCurrent(request)
            publishLoading(request.ledgerId, true)
        }
        try {
            val token = token(request)
            val response = api.projects(authorization(token), requireNotNull(request.ledgerId))
            synchronized(lock) {
                ensureCurrent(request)
                if (!response.isSuccessful) throw projectError(response.code())
                val payload = requireNotNull(response.body())
                val option = request.context.ledgers.single { it.id == request.ledgerId }
                check(payload.ledgerId == request.ledgerId && payload.role == option.role) { "项目账本响应不匹配" }
                check(payload.projects.map { it.projectId }.distinct().size == payload.projects.size)
                payload.projects.forEach {
                    check(it.ledgerId == request.ledgerId)
                    validateBinding(request.context, it)
                }
                val previous = readCache(request.context, request.ledgerId)?.projects.orEmpty()
                val projects = payload.projects.map { incoming ->
                    (previous + scopeBindings.values).filter {
                        it.ledgerId == incoming.ledgerId && it.projectId == incoming.projectId
                    }.fold(incoming) { newest, candidate ->
                        if (candidate.version > newest.version) candidate else newest
                    }
                }.toMutableList()
                // A scope mutation may finish after this full-list request began.
                scopeBindings.values.filter { it.ledgerId == request.ledgerId }.forEach { binding ->
                    if (projects.none { it.projectId == binding.projectId }) projects += binding
                }
                writeCache(request.context, request.ledgerId, ProjectLedgerCache(
                    role = payload.role, projects = projects, complete = true
                ))
                publishCachedStates(request.context, liveLedgerId = request.ledgerId)
            }
        } catch (error: CancellationException) {
            synchronized(lock) {
                if (isCurrent(request)) publishLoading(request.ledgerId, false)
            }
            throw error
        } catch (error: Exception) {
            synchronized(lock) {
                if (isCurrent(request)) publishLoading(request.ledgerId, false, error.message)
            }
            throw error
        }
    }

    private fun synchronizeContext(): Context {
        val ledger = ledgerContext.state.value
        val auth = authManager.state.value
        val current = Context(
            auth, ledger.selection, ledger.ledgers,
            ledger.isSignedIn && ledger.accountId == (auth as? AuthState.SignedIn)?.accountId,
            preferences.boundAccountId.value
        )
        if (current != context) {
            context = current
            contextVersion++
            requests.clear()
            scopeBindings.clear()
            publishCachedStates(current)
        }
        return current
    }

    private fun publishCachedStates(current: Context, liveLedgerId: String? = null) {
        fun state(ledgerId: String?, defaultRoom: Boolean): ProjectLedgerState {
            val account = if (defaultRoom) current.boundAccountId ?: current.accountId else current.accountId
            val cache = ledgerId?.let { readCache(current, it) }
            val previous = if (defaultRoom) _defaultLedgerState.value else _currentLedgerState.value
            return ProjectLedgerState(
                accountId = account, ledgerId = ledgerId,
                role = current.ledgers.firstOrNull { it.id == ledgerId }?.role,
                projects = cache?.projects?.map(ProjectBindingDto::toModel).orEmpty(),
                availability = when {
                    cache == null -> ProjectDefaultsAvailability.UNAVAILABLE
                    ledgerId == liveLedgerId -> ProjectDefaultsAvailability.LIVE
                    else -> ProjectDefaultsAvailability.CACHED
                },
                isLoading = previous.contextVersion == contextVersion && previous.isLoading && ledgerId != liveLedgerId,
                refreshedAtMillis = cache?.refreshedAtMillis,
                contextVersion = contextVersion
            )
        }
        _currentLedgerState.value = state(current.selectedLedgerId(), false)
        _defaultLedgerState.value = state(current.defaultLedgerId(), true)
    }

    private fun publishLoading(ledgerId: String?, loading: Boolean, error: String? = null) {
        listOf(_currentLedgerState, _defaultLedgerState).forEach {
            if (it.value.ledgerId == ledgerId) it.value = it.value.copy(isLoading = loading, errorMessage = error)
        }
    }

    private fun readCache(current: Context, ledgerId: String): ProjectLedgerCache? {
        val accountId = current.accountId ?: return null
        val raw = preferences.projectCacheJson(accountId, ledgerId) ?: return null
        return try {
            json.decodeFromString(ProjectLedgerCache.serializer(), raw).takeIf { cache ->
                cache.complete && cache.role == current.ledgers.firstOrNull { it.id == ledgerId }?.role &&
                    cache.projects.all { it.ledgerId == ledgerId } &&
                    cache.projects.map { it.projectId }.distinct().size == cache.projects.size
            }?.also { cache ->
                cache.projects.forEach { validateBinding(current, it) }
            }?.copy(refreshedAtMillis = preferences.projectCacheRefreshedAt(accountId, ledgerId))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(current: Context, ledgerId: String, cache: ProjectLedgerCache) {
        check(current == synchronizeContext())
        preferences.updateProjectCache(
            requireNotNull(current.accountId), ledgerId,
            json.encodeToString(ProjectLedgerCache.serializer(), cache), now().toEpochMilli()
        )
    }

    private fun mergeBinding(current: Context, binding: ProjectBindingDto): ProjectBindingDto {
        val existing = readCache(current, binding.ledgerId)
        val key = binding.ledgerId to binding.projectId
        val newest = (existing?.projects.orEmpty() + listOfNotNull(scopeBindings[key]))
            .filter { it.projectId == binding.projectId }
            .fold(binding) { value, other -> if (other.version > value.version) other else value }
        scopeBindings[key] = newest
        if (existing != null) {
            writeCache(current, binding.ledgerId, existing.copy(
                projects = existing.projects.filterNot { it.projectId == binding.projectId } + newest
            ))
        }
        return newest
    }

    private fun acceptScope(request: Request, body: ProjectScopeResponse): ProjectScopeResponse {
        check(body.projectId.isNotBlank() && body.name.isNotBlank())
        check(body.ledgers.map { it.ledgerId }.distinct().size == body.ledgers.size)
        body.ledgers.forEach {
            check(it.projectId == body.projectId && it.name == body.name) { "项目响应不匹配" }
            validateBinding(request.context, it)
        }
        val result = body.copy(ledgers = body.ledgers.map { mergeBinding(request.context, it) })
        publishCachedStates(request.context)
        return result
    }

    private fun validateBinding(current: Context, binding: ProjectBindingDto) {
        val option = current.ledgers.single { it.id == binding.ledgerId }
        check(option.role in setOf("OWNER", "EDITOR", "VIEWER"))
        check(binding.projectId.isNotBlank() && binding.name.isNotBlank() && binding.version > 0 &&
            binding.canEdit == option.canEdit && binding.timeZone == "Asia/Shanghai") { "项目响应无效" }
        validateDates(binding.startDate, binding.endDate)
    }

    private fun validateDates(startDate: String?, endDate: String?) {
        val start = startDate?.let(LocalDate::parse)
        val end = endDate?.let(LocalDate::parse)
        require(start == null || end == null || start <= end) { "项目结束日期不能早于开始日期" }
    }

    private fun begin(key: String, ledgerId: String? = null): Request {
        val current = synchronizeContext()
        check(current.accountId != null && current.isSignedIn) { "请先登录" }
        val epoch = ++nextRequest
        requests[key] = epoch
        return Request(current, contextVersion, key, epoch, ledgerId)
    }

    private fun isCurrent(request: Request): Boolean =
        synchronizeContext() == request.context && contextVersion == request.contextVersion &&
            requests[request.key] == request.epoch

    private fun ensureCurrent(request: Request) {
        if (!isCurrent(request)) throw LedgerSelectionChangedException()
    }

    private suspend fun token(request: Request): AccessToken {
        synchronized(lock) { ensureCurrent(request) }
        val token = authManager.acquireToken() ?: throw IllegalStateException("请先登录")
        synchronized(lock) {
            ensureCurrent(request)
            if (token.accountId != request.context.accountId) throw LedgerSelectionChangedException()
        }
        return token
    }

    private fun authorization(token: AccessToken) = "Bearer ${token.value}"

    private fun projectError(code: Int) = IOException(when (code) {
        400 -> "项目请求无效"
        403 -> "没有项目管理权限"
        409 -> "项目配置已变更，请刷新后重试"
        503 -> "项目服务暂不可用"
        else -> "项目请求失败（HTTP $code）"
    })

    private data class Context(
        val auth: AuthState,
        val selection: LedgerSelection,
        val ledgers: List<LedgerOption>,
        val isSignedIn: Boolean,
        val boundAccountId: String?
    ) {
        val accountId: String? get() = (auth as? AuthState.SignedIn)?.accountId
        fun selectedOption() = ledgers.firstOrNull { it.id == selection.ledgerId }
        fun selectedLedgerId() = selectedOption()?.id?.takeIf {
            isSignedIn && accountId != null && it != LOCAL_LEDGER_ID &&
                (selectedOption()?.isLocal != true || boundAccountId == null || boundAccountId == accountId)
        }
        fun defaultLedgerId() = ledgers.firstOrNull { it.isLocal && it.role == "OWNER" }?.id?.takeIf {
            isSignedIn && accountId != null && it != LOCAL_LEDGER_ID &&
                (boundAccountId == null || boundAccountId == accountId)
        }
    }

    private data class Request(
        val context: Context, val contextVersion: Long,
        val key: String, val epoch: Long, val ledgerId: String?
    )

    @Serializable
    private data class ProjectLedgerCache(
        val role: String? = null,
        val projects: List<ProjectBindingDto> = emptyList(),
        val complete: Boolean = false,
        val refreshedAtMillis: Long? = null
    )
}
