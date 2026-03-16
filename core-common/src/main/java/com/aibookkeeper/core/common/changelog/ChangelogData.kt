package com.aibookkeeper.core.common.changelog

data class ChangelogEntry(
    val version: String,
    val date: String,
    val highlights: List<String>
)

val CHANGELOG = listOf(
    ChangelogEntry(
        version = "1.0.1",
        date = "2026-03-16",
        highlights = listOf(
            "✨ AI 记账按钮支持长按语音录入（首页 + 记账页）",
            "📸 新增通知栏「截图记账」一键截屏识别功能",
            "📱 新增支付页面监听（无障碍服务），自动检测微信/支付宝付款",
            "⚙️ 设置页新增智能记账开关和支付页面规则管理",
            "🖼️ 修复启动画面闪烁，全程只显示品牌 Logo",
            "📷 拍照/上传按钮移入输入框，界面更紧凑",
            "🔧 修复 Android 9 设备安装失败问题",
            "🏷️ 支持通过 GitHub Release 发布 APK"
        )
    ),
    ChangelogEntry(
        version = "1.0.0",
        date = "2026-03-15",
        highlights = listOf(
            "🚀 首次发布",
            "📝 支持文字、语音、拍照多种记账方式",
            "🤖 Azure OpenAI 智能识别消费信息",
            "🔔 通知栏快捷记账（微信/支付宝/淘宝/拼多多）",
            "📊 消费统计与分类分析",
            "💾 本地 Room 数据库，离线优先"
        )
    )
)
