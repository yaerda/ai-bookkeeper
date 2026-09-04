package com.aibookkeeper.feature.sync.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.POST
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
    val deletedAt: Long?
)

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
    val isDefault: Boolean = false
)

@Serializable
data class FamilyInvitationDto(
    val id: String,
    val ledgerId: String,
    val ledgerName: String,
    val inviterEmail: String,
    val role: String
)

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
    val role: String
)

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

interface SyncApi {
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
}
