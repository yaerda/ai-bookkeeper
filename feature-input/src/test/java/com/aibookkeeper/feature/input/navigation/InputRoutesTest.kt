package com.aibookkeeper.feature.input.navigation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InputRoutesTest {

    // ── Route constants ──────────────────────────────────────────────────

    @Nested
    inner class RouteConstants {

        @Test
        fun should_haveCorrectHomeRoute_when_accessed() {
            assertEquals("home", InputRoutes.HOME)
        }

        @Test
        fun should_haveCorrectTextInputRoute_when_accessed() {
            assertEquals("text_input?categoryId={categoryId}", InputRoutes.TEXT_INPUT)
        }

        @Test
        fun should_haveCorrectTextInputBaseRoute_when_accessed() {
            assertEquals("text_input", InputRoutes.TEXT_INPUT_BASE)
        }

        @Test
        fun should_haveCorrectVoiceInputRoute_when_accessed() {
            assertEquals("voice_input", InputRoutes.VOICE_INPUT)
        }

        @Test
        fun should_haveCorrectManualFormRoute_when_accessed() {
            assertEquals("manual_form", InputRoutes.MANUAL_FORM)
        }

        @Test
        fun should_haveCorrectBillsRoute_when_accessed() {
            assertEquals("bills", InputRoutes.BILLS)
        }

        @Test
        fun should_haveCorrectConfirmRoute_when_accessed() {
            assertEquals("confirm/{extractionJson}", InputRoutes.CONFIRM)
        }

        @Test
        fun should_haveCorrectDetailRoute_when_accessed() {
            assertEquals("transaction/{transactionId}", InputRoutes.DETAIL)
        }
    }

    // ── textInput() route builder ────────────────────────────────────────

    @Nested
    inner class TextInputRouteBuilder {

        @Test
        fun should_returnBaseRoute_when_noCategoryId() {
            val route = InputRoutes.textInput()
            assertEquals("text_input", route)
        }

        @Test
        fun should_returnBaseRoute_when_categoryIdNull() {
            val route = InputRoutes.textInput(categoryId = null)
            assertEquals("text_input", route)
        }

        @Test
        fun should_includesCategoryId_when_categoryIdProvided() {
            val route = InputRoutes.textInput(categoryId = 5L)
            assertEquals("text_input?categoryId=5", route)
        }

        @Test
        fun should_handleCategoryId1_when_provided() {
            val route = InputRoutes.textInput(categoryId = 1L)
            assertEquals("text_input?categoryId=1", route)
        }

        @Test
        fun should_handleLargeCategoryId_when_provided() {
            val route = InputRoutes.textInput(categoryId = 999999L)
            assertEquals("text_input?categoryId=999999", route)
        }

        @Test
        fun should_handleZeroCategoryId_when_provided() {
            // categoryId=0 is technically valid for the route builder
            val route = InputRoutes.textInput(categoryId = 0L)
            assertEquals("text_input?categoryId=0", route)
        }

        @Test
        fun should_handleNegativeCategoryId_when_provided() {
            // Navigation layer will handle -1 as "no category"
            val route = InputRoutes.textInput(categoryId = -1L)
            assertEquals("text_input?categoryId=-1", route)
        }

        @Test
        fun should_startWithTextInputBase_when_categoryIdProvided() {
            val route = InputRoutes.textInput(categoryId = 42L)
            assertTrue(route.startsWith(InputRoutes.TEXT_INPUT_BASE))
        }

        @Test
        fun should_containQueryParam_when_categoryIdProvided() {
            val route = InputRoutes.textInput(categoryId = 3L)
            assertTrue(route.contains("?categoryId="))
        }

        @Test
        fun should_notContainQueryParam_when_noCategoryId() {
            val route = InputRoutes.textInput()
            assertFalse(route.contains("?"))
            assertFalse(route.contains("categoryId"))
        }
    }
}
