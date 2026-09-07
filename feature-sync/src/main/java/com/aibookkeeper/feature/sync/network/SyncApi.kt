package com.aibookkeeper.feature.sync.network

import com.aibookkeeper.core.data.repository.familyIdentityLabel
import com.aibookkeeper.core.data.model.transactionRecordedByLabel
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

@Serializable
data class SyncTransactionDto(
    val syncId: String,
    val serverVersion: Long,
    val amount: Double,
    val type: String,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryIcon: String?,
    val categoryColor: String?,
    val merchantName: String?,
    val note: String?,
    val originalInput: String?,
    val date: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val source: String,
    val status: String,
    val aiConfidence: Float?,
    val deletedAt: Long?,
    val projectIds: List<String>? = null,
    val recordedByUserId: String? = null,
    val recordedByDisplayName: String? = null,
    val recordedByEmail: String? = null
)

val SyncTransactionDto.recordedByLabel: String?
    get() = transactionRecordedByLabel(recordedByDisplayName, recordedByEmail, recordedByUserId)

@Serializable
data class PushRequest(val transactions: List<SyncTransactionDto>)

@Serializable
data class PushResponse(
    val accepted: List<SyncTransactionDto>,
    val conflicts: List<SyncTransactionDto>
)

@Serializable
data class PullResponse(
    val transactions: List<SyncTransactionDto>,
    val nextCursor: Long,
    val hasMore: Boolean
)

@Serializable
data class FamilyLedgerDto(
    val id: String,
    val name: String,
    val ownerEmail: String,
    val role: String,
    val mode: String,
    val isDefault: Boolean = false,
    val ownerDisplayName: String? = null
) {
    val ownerLabel: String
        get() = familyIdentityLabel(ownerDisplayName, ownerEmail)
}

@Serializable
data class FamilyInvitationDto(
    val id: String,
    val ledgerId: String,
    val ledgerName: String,
    val inviterEmail: String,
    val role: String,
    val inviterDisplayName: String? = null
) {
    val inviterLabel: String
        get() = familyIdentityLabel(inviterDisplayName, inviterEmail)
}

@Serializable
data class FamilyLedgersResponse(
    val ledgers: List<FamilyLedgerDto>,
    val invitations: List<FamilyInvitationDto>
)

@Serializable
data class FamilyMemberDto(
    val id: String,
    val userId: String,
    val email: String,
    val role: String,
    val displayName: String? = null
) {
    val displayLabel: String
        get() = familyIdentityLabel(displayName, email)
}

@Serializable
data class PendingFamilyInvitationDto(
    val id: String,
    val email: String,
    val role: String,
    val createdAt: String? = null
)

@Serializable
data class FamilyMembersResponse(
    val ledger: FamilyLedgerSettingsDto,
    val members: List<FamilyMemberDto>,
    val invitations: List<PendingFamilyInvitationDto>
)

@Serializable
data class FamilyLedgerSettingsDto(
    val id: String,
    val name: String,
    val mode: String
)

@Serializable
data class FamilyInviteRequest(val email: String, val role: String)

@Serializable
data class FamilyRoleRequest(val role: String)

@Serializable
data class FamilySettingsRequest(val mode: String)

@Serializable
data class CreateLedgerRequest(
    val name: String,
    val mode: String = "PERSONAL"
)

@Serializable
data class LedgerCategoryDto(
    val id: Long,
    val name: String,
    val type: String,
    val icon: String,
    val color: String,
    val sortOrder: Int,
    val isSystem: Boolean
)

@Serializable
data class CreateCategoryRequest(
    val name: String,
    val type: String,
    val icon: String,
    val color: String,
    val sortOrder: Int = 1000
)

@Serializable
data class ImportCategoriesRequest(val categories: List<CreateCategoryRequest>)

@Serializable
data class CategoriesResponse(val categories: List<LedgerCategoryDto>)

@Serializable
data class CategoryResponse(val category: LedgerCategoryDto)

@Serializable
data class ProjectBindingDto(
    val projectId: String,
    val ledgerId: String,
    val name: String,
    val enabled: Boolean,
    val startDate: String? = null,
    val endDate: String? = null,
    val timeZone: String = "Asia/Shanghai",
    val version: Long,
    val active: Boolean,
    val canEdit: Boolean
)

@Serializable
data class ProjectsResponse(
    val ledgerId: String,
    val role: String,
    val projects: List<ProjectBindingDto>
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val ledgerIds: List<String>? = null,
    val enabled: Boolean = true,
    val startDate: String? = null,
    val endDate: String? = null
)

@Serializable
data class ProjectScopeResponse(
    val projectId: String,
    val name: String,
    val ledgers: List<ProjectBindingDto>
)

@Serializable
data class ProjectLedgerUpdateRequest(
    val version: Long,
    val enabled: Boolean,
    val startDate: String? = null,
    val endDate: String? = null
)

@Serializable
data class ProjectStatsLedgerDto(
    val ledgerId: String,
    val ledgerName: String,
    val transactionCount: Int,
    val income: String,
    val expense: String,
    val balance: String
)

@Serializable
data class ProjectStatsResponse(
    val projectId: String,
    val name: String,
    val currency: String,
    val transactionCount: Int,
    val income: String,
    val expense: String,
    val balance: String,
    val ledgers: List<ProjectStatsLedgerDto>
)

interface SyncApi {
    @GET("categories")
    suspend fun categories(
        @Header("Authorization") authorization: String,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<CategoriesResponse>

    @POST("categories")
    suspend fun createCategory(
        @Header("Authorization") authorization: String,
        @Body request: CreateCategoryRequest,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<CategoryResponse>

    @POST("categories/import")
    suspend fun importCategories(
        @Header("Authorization") authorization: String,
        @Body request: ImportCategoriesRequest,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<CategoriesResponse>

    @POST("sync/push")
    suspend fun push(
        @Header("Authorization") authorization: String,
        @Body request: PushRequest,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<PushResponse>

    @GET("sync/pull")
    suspend fun pull(
        @Header("Authorization") authorization: String,
        @Query("cursor") cursor: Long,
        @Query("limit") limit: Int = 200,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<PullResponse>

    @GET("family/ledgers")
    suspend fun familyLedgers(
        @Header("Authorization") authorization: String
    ): Response<FamilyLedgersResponse>

    @POST("family/ledgers")
    suspend fun createLedger(
        @Header("Authorization") authorization: String,
        @Body request: CreateLedgerRequest
    ): Response<FamilyLedgerSettingsDto>

    @GET("family/members")
    suspend fun familyMembers(
        @Header("Authorization") authorization: String,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<FamilyMembersResponse>

    @POST("family/invitations")
    suspend fun inviteFamilyMember(
        @Header("Authorization") authorization: String,
        @Body request: FamilyInviteRequest,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<Unit>

    @POST("family/invitations/{invitationId}/accept")
    suspend fun acceptFamilyInvitation(
        @Header("Authorization") authorization: String,
        @Path("invitationId") invitationId: String
    ): Response<Unit>

    @PATCH("family/members/{memberId}")
    suspend fun updateFamilyMember(
        @Header("Authorization") authorization: String,
        @Path("memberId") memberId: String,
        @Body request: FamilyRoleRequest,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<Unit>

    @DELETE("family/members/{memberId}")
    suspend fun removeFamilyMember(
        @Header("Authorization") authorization: String,
        @Path("memberId") memberId: String,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<Unit>

    @PATCH("family/settings")
    suspend fun updateFamilySettings(
        @Header("Authorization") authorization: String,
        @Body request: FamilySettingsRequest,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<FamilyLedgerSettingsDto>

    @GET("projects")
    suspend fun projects(
        @Header("Authorization") authorization: String,
        @Query("ledgerId") ledgerId: String
    ): Response<ProjectsResponse>

    @POST("projects")
    suspend fun createProject(
        @Header("Authorization") authorization: String,
        @Body request: CreateProjectRequest
    ): Response<ProjectScopeResponse>

    @GET("projects/{projectId}/scope")
    suspend fun projectScope(
        @Header("Authorization") authorization: String,
        @Path("projectId") projectId: String
    ): Response<ProjectScopeResponse>

    @PUT("projects/{projectId}/ledgers/{ledgerId}")
    suspend fun updateProjectBinding(
        @Header("Authorization") authorization: String,
        @Path("projectId") projectId: String,
        @Path("ledgerId") ledgerId: String,
        @Body request: ProjectLedgerUpdateRequest
    ): Response<ProjectBindingDto>

    @GET("projects/{projectId}/stats")
    suspend fun projectStats(
        @Header("Authorization") authorization: String,
        @Path("projectId") projectId: String,
        @Query("ledgerId") ledgerId: String? = null
    ): Response<ProjectStatsResponse>
}
