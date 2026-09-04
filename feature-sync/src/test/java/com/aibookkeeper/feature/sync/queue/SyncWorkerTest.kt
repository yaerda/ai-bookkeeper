package com.aibookkeeper.feature.sync.queue

import com.aibookkeeper.feature.sync.auth.AuthenticationRequiredException
import com.aibookkeeper.feature.sync.auth.RetryableAuthenticationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

class SyncWorkerTest {

    @Test
    fun `retries network and transient authentication failures`() {
        assertTrue(isRetryableSyncFailure(IOException("offline")))
        assertTrue(isRetryableSyncFailure(RetryableSyncException("HTTP 429")))
        assertTrue(isRetryableSyncFailure(RetryableSyncException("HTTP 500")))
        assertTrue(
            isRetryableSyncFailure(
                RetryableAuthenticationException(IllegalStateException())
            )
        )
    }

    @Test
    fun `does not retry interaction or account failures`() {
        assertFalse(isRetryableSyncFailure(AuthenticationRequiredException()))
        assertFalse(isRetryableSyncFailure(AccountMismatchException()))
        assertFalse(isRetryableSyncFailure(PermanentSyncException("HTTP 400")))
    }

    @Test
    fun `retries when pending work remains beyond isolated failures`() {
        val report = SyncReport(
            uploaded = 1,
            downloaded = 0,
            conflicts = 0,
            failedSyncIds = setOf("poison"),
            timestamp = Instant.EPOCH
        )

        assertFalse(shouldRetryAfterSync(report, pendingCount = 1))
        assertTrue(shouldRetryAfterSync(report, pendingCount = 2))
    }
}
