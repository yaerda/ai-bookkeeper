package com.aibookkeeper.core.common.util

/**
 * Maps category icon name strings (stored in DB like "ic_food")
 * to displayable emoji characters.
 */
object CategoryIconMapper {
    const val DEFAULT_ICON_KEY = "tag"

    private val iconMap = mapOf(
        "ic_food" to "🍚",
        "ic_transport" to "🚗",
        "ic_shopping" to "🛒",
        "ic_entertainment" to "🎮",
        "ic_housing" to "🏠",
        "ic_medical" to "💊",
        "ic_education" to "📚",
        "ic_communication" to "📱",
        "ic_clothing" to "👔",
        "ic_other" to "📦",
        "ic_salary" to "💰",
        "ic_bonus" to "🎁",
        "ic_parttime" to "💼",
        "ic_investment" to "📈",
        "ic_redpacket" to "🧧",
        "ic_other_income" to "💵",
        "ic_fruit" to "🍎",
        "ic_drink" to "🥤",
        "ic_pet" to "🐱",
        "ic_travel" to "✈️",
        "ic_sport" to "⚽",
        "ic_beauty" to "💄",
        "ic_baby" to "🍼",
        "ic_digital" to "💻",
        "ic_gift" to "🎀",
        "ic_repair" to "🔧",
        DEFAULT_ICON_KEY to "🏷️"
    )

    /** All available icons for user selection */
    val allIcons: List<Pair<String, String>> = iconMap.toList()

    fun isPresetIcon(iconName: String?): Boolean = iconName?.trim()?.let(iconMap::containsKey) == true

    fun getEmoji(iconName: String?): String {
        val normalizedIcon = iconName?.trim()
        return when {
            normalizedIcon.isNullOrEmpty() -> iconMap.getValue(DEFAULT_ICON_KEY)
            isPresetIcon(normalizedIcon) -> iconMap.getValue(normalizedIcon)
            normalizedIcon.startsWith("ic_") || normalizedIcon == DEFAULT_ICON_KEY -> iconMap.getValue(DEFAULT_ICON_KEY)
            else -> normalizedIcon
        }
    }
}
