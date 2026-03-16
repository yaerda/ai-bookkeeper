package com.aibookkeeper.core.data.model

/**
 * Source app identifiers for notification/auto-capture events.
 */
enum class SourceApp(val packageName: String, val displayName: String) {
    WECHAT_PAY("com.tencent.mm", "微信支付"),
    ALIPAY("com.eg.android.AlipayGphone", "支付宝"),
    TAOBAO("com.taobao.taobao", "淘宝"),
    PINDUODUO("com.xunmeng.pinduoduo", "拼多多"),
    SCREENSHOT("", "截图识别"),
    UNKNOWN("", "未知");

    companion object {
        private val packageMap = entries
            .filter { it.packageName.isNotBlank() }
            .associateBy { it.packageName }

        fun fromPackageName(pkg: String): SourceApp =
            packageMap[pkg] ?: UNKNOWN

        /** Packages we listen to for payment notifications */
        val MONITORED_PACKAGES = entries
            .map { it.packageName }
            .filter { it.isNotBlank() }
            .toSet()
    }
}
