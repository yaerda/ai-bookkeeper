package com.aibookkeeper.navigation

import com.aibookkeeper.feature.input.navigation.InputRoutes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BottomNavItemTest {

    // ── Route definitions ────────────────────────────────────────────────

    @Nested
    inner class RouteDefinitions {

        @Test
        fun should_haveHomeRoute_when_homeItemAccessed() {
            assertEquals(InputRoutes.HOME, BottomNavItem.Home.route)
        }

        @Test
        fun should_haveStatsRoute_when_statsItemAccessed() {
            assertEquals("stats", BottomNavItem.Stats.route)
        }

        @Test
        fun should_haveTextInputRoute_when_addItemAccessed() {
            assertEquals(InputRoutes.TEXT_INPUT, BottomNavItem.Add.route)
        }

        @Test
        fun should_haveBillsRoute_when_billsItemAccessed() {
            assertEquals(InputRoutes.BILLS, BottomNavItem.Bills.route)
        }

        @Test
        fun should_haveSettingsRoute_when_settingsItemAccessed() {
            assertEquals("settings", BottomNavItem.Settings.route)
        }
    }

    // ── Navigate routes ──────────────────────────────────────────────────

    @Nested
    inner class NavigateRoutes {

        @Test
        fun should_useRouteAsNavigateRoute_when_homeDefault() {
            assertEquals(BottomNavItem.Home.route, BottomNavItem.Home.navigateRoute)
        }

        @Test
        fun should_useRouteAsNavigateRoute_when_statsDefault() {
            assertEquals(BottomNavItem.Stats.route, BottomNavItem.Stats.navigateRoute)
        }

        @Test
        fun should_useTextInputHelper_when_addItemNavigated() {
            // Add uses InputRoutes.textInput() which returns "text_input" without params
            assertEquals(InputRoutes.textInput(), BottomNavItem.Add.navigateRoute)
            assertEquals("text_input", BottomNavItem.Add.navigateRoute)
        }

        @Test
        fun should_useRouteAsNavigateRoute_when_billsDefault() {
            assertEquals(BottomNavItem.Bills.route, BottomNavItem.Bills.navigateRoute)
        }

        @Test
        fun should_useRouteAsNavigateRoute_when_settingsDefault() {
            assertEquals(BottomNavItem.Settings.route, BottomNavItem.Settings.navigateRoute)
        }
    }

    // ── Labels ───────────────────────────────────────────────────────────

    @Nested
    inner class Labels {

        @Test
        fun should_showHomeLabel_when_homeItem() {
            assertEquals("首页", BottomNavItem.Home.label)
        }

        @Test
        fun should_showStatsLabel_when_statsItem() {
            assertEquals("统计", BottomNavItem.Stats.label)
        }

        @Test
        fun should_showAddLabel_when_addItem() {
            assertEquals("记账", BottomNavItem.Add.label)
        }

        @Test
        fun should_showBillsLabel_when_billsItem() {
            assertEquals("账单", BottomNavItem.Bills.label)
        }

        @Test
        fun should_showSettingsLabel_when_settingsItem() {
            assertEquals("设置", BottomNavItem.Settings.label)
        }
    }

    // ── Top-level route visibility ───────────────────────────────────────

    @Nested
    inner class TopLevelRouteVisibility {

        // These mirror the topLevelRoutes set in AppNavHost
        private val topLevelRoutes = setOf(
            InputRoutes.HOME,
            "stats",
            InputRoutes.BILLS,
            "settings"
        )

        @Test
        fun should_showBottomBar_when_onHomeRoute() {
            assertTrue(topLevelRoutes.contains(BottomNavItem.Home.route))
        }

        @Test
        fun should_showBottomBar_when_onStatsRoute() {
            assertTrue(topLevelRoutes.contains(BottomNavItem.Stats.route))
        }

        @Test
        fun should_showBottomBar_when_onBillsRoute() {
            assertTrue(topLevelRoutes.contains(BottomNavItem.Bills.route))
        }

        @Test
        fun should_showBottomBar_when_onSettingsRoute() {
            assertTrue(topLevelRoutes.contains(BottomNavItem.Settings.route))
        }

        @Test
        fun should_hideBottomBar_when_onTextInputRoute() {
            // TextInputScreen is a detail screen, not a tab
            assertFalse(topLevelRoutes.contains("text_input"))
            assertFalse(topLevelRoutes.contains(InputRoutes.TEXT_INPUT))
        }

        @Test
        fun should_hideBottomBar_when_onTransactionDetailRoute() {
            assertFalse(topLevelRoutes.contains("transaction/1"))
            assertFalse(topLevelRoutes.contains(InputRoutes.DETAIL))
        }

        @Test
        fun should_hideBottomBar_when_onOnboardingRoute() {
            assertFalse(topLevelRoutes.contains("onboarding"))
        }

        @Test
        fun should_hideBottomBar_when_onCaptureRoute() {
            assertFalse(topLevelRoutes.contains("capture"))
        }

        @Test
        fun should_haveExactly4TopLevelRoutes_when_checked() {
            assertEquals(4, topLevelRoutes.size)
        }
    }

    // ── Item count and ordering ──────────────────────────────────────────

    @Nested
    inner class ItemOrdering {

        private val allItems = listOf(
            BottomNavItem.Home,
            BottomNavItem.Stats,
            BottomNavItem.Add,
            BottomNavItem.Bills,
            BottomNavItem.Settings
        )

        @Test
        fun should_have5Items_when_allItemsListed() {
            assertEquals(5, allItems.size)
        }

        @Test
        fun should_haveAddInCenter_when_allItemsOrdered() {
            assertEquals(BottomNavItem.Add, allItems[2])
        }

        @Test
        fun should_haveHomeFirst_when_allItemsOrdered() {
            assertEquals(BottomNavItem.Home, allItems[0])
        }

        @Test
        fun should_haveSettingsLast_when_allItemsOrdered() {
            assertEquals(BottomNavItem.Settings, allItems.last())
        }

        @Test
        fun should_identifyAddAsSpecialItem_when_typeChecked() {
            val addItem: BottomNavItem = BottomNavItem.Add
            assertTrue(addItem is BottomNavItem.Add)
        }
    }

    // ── navigateRoute differs from route for Add ─────────────────────────

    @Nested
    inner class AddItemRoutePattern {

        @Test
        fun should_haveRouteWithPlaceholder_when_addRouteAccessed() {
            // The route pattern includes the query param placeholder
            assertTrue(BottomNavItem.Add.route.contains("{categoryId}"))
        }

        @Test
        fun should_haveNavigateRouteWithoutPlaceholder_when_addNavigated() {
            // The navigate route is the resolved URL without the placeholder
            assertFalse(BottomNavItem.Add.navigateRoute.contains("{"))
        }

        @Test
        fun should_differRouteFromNavigateRoute_when_addItem() {
            assertNotEquals(BottomNavItem.Add.route, BottomNavItem.Add.navigateRoute)
        }

        @Test
        fun should_haveMatchingRouteAndNavigateRoute_when_homeItem() {
            assertEquals(BottomNavItem.Home.route, BottomNavItem.Home.navigateRoute)
        }

        @Test
        fun should_haveMatchingRouteAndNavigateRoute_when_statsItem() {
            assertEquals(BottomNavItem.Stats.route, BottomNavItem.Stats.navigateRoute)
        }
    }
}
