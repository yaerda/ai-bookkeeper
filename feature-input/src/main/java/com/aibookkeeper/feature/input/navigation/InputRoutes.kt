package com.aibookkeeper.feature.input.navigation

object InputRoutes {
    const val HOME = "home"
    const val TEXT_INPUT = "text_input?categoryId={categoryId}"
    const val TEXT_INPUT_BASE = "text_input"
    const val VOICE_INPUT = "voice_input"
    const val MANUAL_FORM = "manual_form"
    const val BILLS = "bills"
    const val CONFIRM = "confirm/{extractionJson}"
    const val DETAIL = "transaction/{transactionId}"

    fun textInput(categoryId: Long? = null): String =
        if (categoryId != null) "text_input?categoryId=$categoryId"
        else "text_input"
}
