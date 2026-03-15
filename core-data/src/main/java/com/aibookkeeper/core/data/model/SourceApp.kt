package com.aibookkeeper.core.data.model

/**
 * Source app identifiers for notification/auto-capture events.
 */
enum class SourceApp(val packageName: String, val displayName: String) {
    WECHAT_PAY("com.tencent.mm", "微信支付"),
    ALIPAY("com.eg.android.AlipayGphone", "支付宝"),
    TAOBAO("com.taobao.taobao", "淘宝"),
    PINDUODUO("com.xunmeng.pinduoduo", "拼多多"),
    UNKNOWN("", "未知");

    companion object {
        private val packageMap = entries.associateBy { it.packageName }

        fun fromPackageName(pkg: String): SourceApp =
            packageMap[pkg] ?: UNKNOWN

        /** Packages we listen to for payment notifications */
        val MONITORED_PACKAGES = entries
            .filter { it != UNKNOWN }
            .map { it.packageName }
            .toSet()
    }
}
