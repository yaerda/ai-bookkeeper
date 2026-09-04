package com.aibookkeeper.feature.sync.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.network.FamilyInvitationDto
import com.aibookkeeper.feature.sync.network.CreateLedgerRequest
import com.aibookkeeper.feature.sync.network.FamilyInviteRequest
import com.aibookkeeper.feature.sync.network.FamilyLedgerDto
import com.aibookkeeper.feature.sync.network.FamilyMemberDto
import com.aibookkeeper.feature.sync.network.FamilyRoleRequest
import com.aibookkeeper.feature.sync.network.FamilySettingsRequest
import com.aibookkeeper.feature.sync.network.PendingFamilyInvitationDto
import com.aibookkeeper.feature.sync.network.SyncApi
import com.aibookkeeper.core.data.repository.LedgerContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class FamilyUiState(
    val authState: AuthState = AuthState.Loading,
    val ledgers: List<FamilyLedgerDto> = emptyList(),
    val selectedLedgerId: String? = null,
    val invitations: List<FamilyInvitationDto> = emptyList(),
    val members: List<FamilyMemberDto> = emptyList(),
    val pendingInvitations: List<PendingFamilyInvitationDto> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null
) {
    val selectedLedger: FamilyLedgerDto?
        get() = ledgers.firstOrNull { it.id == selectedLedgerId }
    val canEdit: Boolean
        get() = selectedLedger?.role in setOf("OWNER", "EDITOR")
    val isOwner: Boolean
        get() = selectedLedger?.role == "OWNER"
}

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val api: SyncApi,
    private val ledgerContext: LedgerContext
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilyUiState())
    val uiState: StateFlow<FamilyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authManager.state.collectLatest { authState ->
                _uiState.value = _uiState.value.copy(authState = authState)
                if (authState is AuthState.SignedIn) {
                    refresh()
                } else {
                    _uiState.value = FamilyUiState(authState = authState)
                }
            }
        }
    }

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            authManager.signIn(activity)
        }
    }

    fun refresh() {
        launchRequest {
            val authorization = authorization()
            val ledgerResponse = api.familyLedgers(authorization)
            checkResponse(ledgerResponse.code(), ledgerResponse.isSuccessful)
            val payload = requireNotNull(ledgerResponse.body())
            val selectedId = _uiState.value.selectedLedgerId
                ?.takeIf { id -> payload.ledgers.any { it.id == id } }
                ?: payload.ledgers.firstOrNull()?.id
            _uiState.value = _uiState.value.copy(
                ledgers = payload.ledgers,
                selectedLedgerId = selectedId,
                invitations = payload.invitations
            )
            loadSelectedLedger(authorization, selectedId)
        }
    }

    fun selectLedger(ledgerId: String) {
        _uiState.value = _uiState.value.copy(selectedLedgerId = ledgerId)
        launchRequest {
            loadSelectedLedger(authorization(), ledgerId)
        }
    }

    fun createLedger(name: String, mode: String) {
        launchRequest("账本已创建") {
            val authorization = authorization()
            val response = api.createLedger(
                authorization,
                CreateLedgerRequest(name.trim(), mode)
            )
            checkResponse(response.code(), response.isSuccessful)
            val created = requireNotNull(response.body())
            refreshInternal(authorization, created.id)
        }
    }

    fun invite(email: String, canEdit: Boolean) {
        launchRequest("邀请已发送") {
            val authorization = authorization()
            val ledgerId = requireNotNull(_uiState.value.selectedLedgerId)
            val response = api.inviteFamilyMember(
                authorization,
                FamilyInviteRequest(
                    email = email.trim(),
                    role = if (canEdit) "EDITOR" else "VIEWER"
                ),
                ledgerId
            )
            checkResponse(response.code(), response.isSuccessful)
            refreshInternal(authorization)
        }
    }

    fun acceptInvitation(invitationId: String) {
        launchRequest("已加入家庭账本") {
            val authorization = authorization()
            val response = api.acceptFamilyInvitation(
                authorization,
                invitationId
            )
            checkResponse(response.code(), response.isSuccessful)
            refreshInternal(authorization)
        }
    }

    fun updateMember(memberId: String, canEdit: Boolean) {
        launchRequest("成员权限已更新") {
            val authorization = authorization()
            val response = api.updateFamilyMember(
                authorization,
                memberId,
                FamilyRoleRequest(if (canEdit) "EDITOR" else "VIEWER"),
                _uiState.value.selectedLedgerId
            )
            checkResponse(response.code(), response.isSuccessful)
            refreshInternal(authorization)
        }
    }

    fun removeMember(memberId: String) {
        launchRequest("成员已移除") {
            val authorization = authorization()
            val response = api.removeFamilyMember(
                authorization,
                memberId,
                _uiState.value.selectedLedgerId
            )
            checkResponse(response.code(), response.isSuccessful)
            refreshInternal(authorization)
        }
    }

    fun convertLedger(mode: String) {
        launchRequest(
            if (mode == "FAMILY") "已转换为家庭账本" else "已转换为个人账本"
        ) {
            val authorization = authorization()
            val response = api.updateFamilySettings(
                authorization,
                FamilySettingsRequest(mode = mode),
                _uiState.value.selectedLedgerId
            )
            checkResponse(response.code(), response.isSuccessful)
            refreshInternal(authorization)
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private suspend fun refreshInternal(
        authorization: String,
        preferredLedgerId: String? = null
    ) {
        val ledgerResponse = api.familyLedgers(authorization)
        checkResponse(ledgerResponse.code(), ledgerResponse.isSuccessful)
        val payload = requireNotNull(ledgerResponse.body())
        val selectedId = preferredLedgerId
            ?.takeIf { id -> payload.ledgers.any { it.id == id } }
            ?: _uiState.value.selectedLedgerId
            ?.takeIf { id -> payload.ledgers.any { it.id == id } }
            ?: payload.ledgers.firstOrNull()?.id
        _uiState.value = _uiState.value.copy(
            ledgers = payload.ledgers,
            selectedLedgerId = selectedId,
            invitations = payload.invitations
        )
        loadSelectedLedger(authorization, selectedId)
        ledgerContext.refresh()
    }

    private suspend fun loadSelectedLedger(
        authorization: String,
        ledgerId: String?
    ) {
        if (ledgerId == null) return
        val selected = _uiState.value.ledgers.firstOrNull { it.id == ledgerId }
        if (selected?.role == "OWNER") {
            val membersResponse = api.familyMembers(authorization, ledgerId)
            checkResponse(membersResponse.code(), membersResponse.isSuccessful)
            val membersPayload = requireNotNull(membersResponse.body())
            _uiState.value = _uiState.value.copy(
                members = membersPayload.members,
                pendingInvitations = membersPayload.invitations
            )
        } else {
            _uiState.value = _uiState.value.copy(
                members = emptyList(),
                pendingInvitations = emptyList()
            )
        }
    }

    private suspend fun authorization(): String {
        val token = authManager.acquireToken()
            ?: throw IllegalStateException("请先登录")
        return "Bearer ${token.value}"
    }

    private fun launchRequest(
        successMessage: String? = null,
        operation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                message = null
            )
            try {
                operation()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = successMessage
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = error.message ?: "操作失败"
                )
            }
        }
    }

    private fun checkResponse(code: Int, successful: Boolean) {
        if (!successful) {
            throw IllegalStateException(
                when (code) {
                    401 -> "登录已失效，请重新登录"
                    403 -> "没有此账本的操作权限"
                    409 -> "账单已被其他成员修改，请刷新后重试"
                    else -> "请求失败（HTTP $code）"
                }
            )
        }
    }
}
