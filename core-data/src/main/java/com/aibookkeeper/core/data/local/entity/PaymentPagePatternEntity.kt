package com.aibookkeeper.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payment_page_patterns",
    indices = [Index(value = ["packageName"])]
)
data class PaymentPagePatternEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "packageName")
    val packageName: String,

    @ColumnInfo(name = "appDisplayName")
    val appDisplayName: String,

    @ColumnInfo(name = "keywords")
    val keywords: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "isEnabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "isSystem")
    val isSystem: Boolean = false,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis()
)
