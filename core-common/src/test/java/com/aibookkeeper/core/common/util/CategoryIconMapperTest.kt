package com.aibookkeeper.core.common.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CategoryIconMapperTest {

    // ── Known icons ──────────────────────────────────────────────────────

    @Nested
    inner class KnownIcons {

        @Test
        fun should_returnFoodEmoji_when_iconIsFoodType() {
            assertEquals("🍚", CategoryIconMapper.getEmoji("ic_food"))
        }

        @Test
        fun should_returnTransportEmoji_when_iconIsTransport() {
            assertEquals("🚗", CategoryIconMapper.getEmoji("ic_transport"))
        }

        @Test
        fun should_returnShoppingEmoji_when_iconIsShopping() {
            assertEquals("🛒", CategoryIconMapper.getEmoji("ic_shopping"))
        }

        @Test
        fun should_returnEntertainmentEmoji_when_iconIsEntertainment() {
            assertEquals("🎮", CategoryIconMapper.getEmoji("ic_entertainment"))
        }

        @Test
        fun should_returnHousingEmoji_when_iconIsHousing() {
            assertEquals("🏠", CategoryIconMapper.getEmoji("ic_housing"))
        }

        @Test
        fun should_returnMedicalEmoji_when_iconIsMedical() {
            assertEquals("💊", CategoryIconMapper.getEmoji("ic_medical"))
        }

        @Test
        fun should_returnEducationEmoji_when_iconIsEducation() {
            assertEquals("📚", CategoryIconMapper.getEmoji("ic_education"))
        }

        @Test
        fun should_returnCommunicationEmoji_when_iconIsCommunication() {
            assertEquals("📱", CategoryIconMapper.getEmoji("ic_communication"))
        }

        @Test
        fun should_returnClothingEmoji_when_iconIsClothing() {
            assertEquals("👔", CategoryIconMapper.getEmoji("ic_clothing"))
        }

        @Test
        fun should_returnOtherEmoji_when_iconIsOther() {
            assertEquals("📦", CategoryIconMapper.getEmoji("ic_other"))
        }

        @Test
        fun should_returnSalaryEmoji_when_iconIsSalary() {
            assertEquals("💰", CategoryIconMapper.getEmoji("ic_salary"))
        }

        @Test
        fun should_returnBonusEmoji_when_iconIsBonus() {
            assertEquals("🎁", CategoryIconMapper.getEmoji("ic_bonus"))
        }

        @Test
        fun should_returnParttimeEmoji_when_iconIsParttime() {
            assertEquals("💼", CategoryIconMapper.getEmoji("ic_parttime"))
        }

        @Test
        fun should_returnInvestmentEmoji_when_iconIsInvestment() {
            assertEquals("📈", CategoryIconMapper.getEmoji("ic_investment"))
        }

        @Test
        fun should_returnRedPacketEmoji_when_iconIsRedPacket() {
            assertEquals("🧧", CategoryIconMapper.getEmoji("ic_redpacket"))
        }

        @Test
        fun should_returnOtherIncomeEmoji_when_iconIsOtherIncome() {
            assertEquals("💵", CategoryIconMapper.getEmoji("ic_other_income"))
        }
    }

    // ── Unknown / null icons ─────────────────────────────────────────────

    @Nested
    inner class UnknownIcons {

        @Test
        fun should_returnDefaultEmoji_when_iconIsNull() {
            assertEquals("📦", CategoryIconMapper.getEmoji(null))
        }

        @Test
        fun should_returnDefaultEmoji_when_iconIsUnknown() {
            assertEquals("📦", CategoryIconMapper.getEmoji("ic_unknown"))
        }

        @Test
        fun should_returnDefaultEmoji_when_iconIsEmptyString() {
            assertEquals("📦", CategoryIconMapper.getEmoji(""))
        }
    }
}
