package com.aibookkeeper.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["date"]),
        Index(value = ["categoryId"]),
        Index(value = ["type"]),
        Index(value = ["status"]),
        Index(value = ["syncStatus"]),
        Index(value = ["date", "type"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "amount")
    val amount: Double,

    @ColumnInfo(name = "type")
    val type: String,                       // "INCOME" | "EXPENSE"

    @ColumnInfo(name = "categoryId")
    val categoryId: Long?,

    @ColumnInfo(name = "merchantName")
    val merchantName: String? = null,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "originalInput")
    val originalInput: String? = null,

    @ColumnInfo(name = "date")
    val date: Long,                         // epoch millis

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "source")
    val source: String,                     // MANUAL|TEXT_AI|VOICE_AI|PHOTO_AI|AUTO_CAPTURE|NOTIFICATION_QUICK

    @ColumnInfo(name = "status")
    val status: String = "CONFIRMED",       // CONFIRMED | PENDING

    @ColumnInfo(name = "syncStatus")
    val syncStatus: String = "LOCAL",       // LOCAL | PENDING_SYNC | SYNCED

    @ColumnInfo(name = "aiConfidence")
    val aiConfidence: Float? = null,

    @ColumnInfo(name = "aiRawResponse")
    val aiRawResponse: String? = null
)
