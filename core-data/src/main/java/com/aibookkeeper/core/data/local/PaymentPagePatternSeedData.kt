package com.aibookkeeper.core.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.aibookkeeper.core.data.local.entity.PaymentPagePatternEntity

object PaymentPagePatternSeedData {

    val defaultPatterns = listOf(
        PaymentPagePatternEntity(
            packageName = "com.tencent.mm",
            appDisplayName = "微信支付",
            keywords = "支付成功,转账成功,已付款,微信支付,付款给,收款到账",
            description = "微信支付/转账成功页面",
            isSystem = true
        ),
        PaymentPagePatternEntity(
            packageName = "com.eg.android.AlipayGphone",
            appDisplayName = "支付宝",
            keywords = "付款成功,交易成功,转账成功,支付宝,已支付,付款详情",
            description = "支付宝付款/转账成功页面",
            isSystem = true
        ),
        PaymentPagePatternEntity(
            packageName = "com.taobao.taobao",
            appDisplayName = "淘宝",
            keywords = "订单已付款,支付成功,已买到,交易成功,付款成功",
            description = "淘宝订单支付成功页面",
            isSystem = true
        ),
        PaymentPagePatternEntity(
            packageName = "com.xunmeng.pinduoduo",
            appDisplayName = "拼多多",
            keywords = "支付成功,拼单成功,已支付,付款成功,订单支付",
            description = "拼多多支付成功页面",
            isSystem = true
        )
    )

    fun insertDefaults(db: SupportSQLiteDatabase) {
        defaultPatterns.forEach { pattern ->
            db.execSQL(
                """
                INSERT INTO payment_page_patterns (
                    packageName,
                    appDisplayName,
                    keywords,
                    description,
                    isEnabled,
                    isSystem,
                    createdAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    pattern.packageName,
                    pattern.appDisplayName,
                    pattern.keywords,
                    pattern.description,
                    if (pattern.isEnabled) 1 else 0,
                    if (pattern.isSystem) 1 else 0,
                    pattern.createdAt
                )
            )
        }
    }
}
