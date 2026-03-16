package com.aibookkeeper.core.data.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UpdateCheckerTest {

    @Nested
    inner class IsNewerVersion {

        @Test
        fun should_returnTrue_when_remoteVersionIsHigher() {
            assertTrue(UpdateChecker.isNewerVersion("1.0.2", "1.0.1"))
        }

        @Test
        fun should_returnFalse_when_versionsAreEqual() {
            assertFalse(UpdateChecker.isNewerVersion("1.0.1", "1.0.1"))
        }

        @Test
        fun should_returnFalse_when_remoteVersionIsLower() {
            assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.1"))
        }

        @Test
        fun should_treatMissingSegmentsAsZero() {
            assertTrue(UpdateChecker.isNewerVersion("1.1", "1.0.9"))
            assertFalse(UpdateChecker.isNewerVersion("1.0", "1.0.1"))
        }
    }
}
