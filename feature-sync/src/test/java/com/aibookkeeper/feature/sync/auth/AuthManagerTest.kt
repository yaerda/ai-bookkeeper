package com.aibookkeeper.feature.sync.auth

import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class AuthManagerTest {

    @Test
    fun `network failure is retryable`() {
        val error = MsalClientException(
            MsalClientException.DEVICE_NETWORK_NOT_AVAILABLE,
            "offline"
        )

        assertInstanceOf(
            RetryableAuthenticationException::class.java,
            classifySilentAuthException(error)
        )
    }

    @Test
    fun `server outage is retryable`() {
        val error = MsalServiceException(
            MsalServiceException.SERVICE_NOT_AVAILABLE,
            "unavailable",
            503,
            null
        )

        assertInstanceOf(
            RetryableAuthenticationException::class.java,
            classifySilentAuthException(error)
        )
    }

    @Test
    fun `interaction-required failure is not transient`() {
        val error = MsalUiRequiredException(
            MsalUiRequiredException.NO_TOKENS_FOUND,
            "sign in"
        )

        assertSame(error, classifySilentAuthException(error))
    }
}
