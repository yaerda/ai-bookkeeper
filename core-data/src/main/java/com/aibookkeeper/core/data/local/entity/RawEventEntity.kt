package com.aibookkeeper.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores raw input events (notifications, text, OCR) before AI extraction.
 * Useful for auditing, retrying failed extractions, and deduplication.
 */
@Entity(
    tableName = "raw_events",
    indices = [
        Index(value = ["sourceApp"]),
        Index(value = ["status"]),
        Index(value = ["capturedAt"]),
        Index(value = ["contentHash"], unique = true)
    ]
)
data class RawEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "sourceApp")
    val sourceApp: String,          // WECHAT_PAY | ALIPAY | TAOBAO | PINDUODUO | MANUAL | OCR

    @ColumnInfo(name = "rawContent")
    val rawContent: String,

    @ColumnInfo(name = "contentHash")
    val contentHash: String,        // SHA-256 for deduplication

    @ColumnInfo(name = "capturedAt")
    val capturedAt: Long,

    @ColumnInfo(name = "status")
    val status: String = "PENDING", // PENDING | EXTRACTED | FAILED | IGNORED

    @ColumnInfo(name = "transactionId")
    val transactionId: Long? = null,

    @ColumnInfo(name = "extractionError")
    val extractionError: String? = null,

    @ColumnInfo(name = "retryCount")
    val retryCount: Int = 0
)
