package com.aibookkeeper.feature.sync.auth

import android.app.Activity
import android.content.Context
import com.aibookkeeper.feature.sync.R
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val accountId: String, val email: String) : AuthState
    data class Error(val message: String) : AuthState
}

data class AccessToken(
    val value: String,
    val accountId: String
)

interface TokenProvider {
    suspend fun acquireToken(): AccessToken?
    suspend fun invalidate()
}

class AuthenticationRequiredException(cause: Throwable? = null) :
    IllegalStateException("请重新登录", cause)
class RetryableAuthenticationException(cause: Throwable) :
    IllegalStateException("暂时无法刷新登录状态", cause)
private class SignInCancelledException :
    IllegalStateException("登录已取消")

@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) : TokenProvider {

    private val application = CompletableDeferred<ISingleAccountPublicClientApplication>()
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    @Volatile
    private var initializationStarted = false

    fun initialize() {
        if (initializationStarted) return
        synchronized(this) {
            if (initializationStarted) return
            initializationStarted = true
            PublicClientApplication.createSingleAccountPublicClientApplication(
                context,
                R.raw.auth_config_single_account,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(
                        applicationInstance: ISingleAccountPublicClientApplication
                    ) {
                        application.complete(applicationInstance)
                        refreshAccount(applicationInstance)
                    }

                    override fun onError(exception: MsalException) {
                        application.completeExceptionally(exception)
                        _state.value = AuthState.Error(
                            exception.message ?: "登录服务初始化失败"
                        )
                    }
                }
            )
        }
    }

    suspend fun signIn(activity: Activity): Result<AuthState.SignedIn> {
        val app = try {
            awaitApplication()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _state.value = AuthState.Error(error.message ?: "登录服务初始化失败")
            return Result.failure(error)
        }

        return try {
            currentAccount(app)?.let { account ->
                try {
                    acquireTokenSilent(app, account)
                    val signedIn = account.toSignedIn()
                    _state.value = signedIn
                    return Result.success(signedIn)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: MsalUiRequiredException) {
                    clearCurrentAccount(app)
                }
            }

            Result.success(interactiveSignIn(app, activity))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cancelled: SignInCancelledException) {
            _state.value = AuthState.SignedOut
            Result.failure(cancelled)
        } catch (error: Exception) {
            val restoredAccount = try {
                currentAccount(app)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (restoredAccount != null) {
                val signedIn = restoredAccount.toSignedIn()
                _state.value = signedIn
                Result.success(signedIn)
            } else {
                _state.value = AuthState.Error(error.message ?: "登录失败")
                Result.failure(error)
            }
        }
    }

    private suspend fun interactiveSignIn(
        app: ISingleAccountPublicClientApplication,
        activity: Activity
    ): AuthState.SignedIn =
        suspendCancellableCoroutine { continuation ->
            app.signIn(
                SignInParameters.builder()
                    .withActivity(activity)
                    .withScopes(SCOPES)
                    .withCallback(object : AuthenticationCallback {
                        override fun onSuccess(authenticationResult: IAuthenticationResult) {
                            val signedIn = authenticationResult.account.toSignedIn()
                            _state.value = signedIn
                            continuation.resume(signedIn)
                        }

                        override fun onError(exception: MsalException) {
                            continuation.resumeWithException(exception)
                        }

                        override fun onCancel() {
                            continuation.resumeWithException(SignInCancelledException())
                        }
                    })
                    .build()
            )
        }

    suspend fun signOut(): Result<Unit> = runCatching {
        val app = awaitApplication()
        suspendCancellableCoroutine { continuation ->
            app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    _state.value = AuthState.SignedOut
                    continuation.resume(Unit)
                }

                override fun onError(exception: MsalException) {
                    _state.value = AuthState.Error(exception.message ?: "退出登录失败")
                    continuation.resumeWithException(exception)
                }
            })
        }
    }

    override suspend fun acquireToken(): AccessToken? {
        val app = awaitApplication()
        val account = currentAccount(app) ?: return null
        return try {
            acquireTokenSilent(app, account)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: MsalUiRequiredException) {
            _state.value = AuthState.SignedOut
            throw AuthenticationRequiredException(exception)
        } catch (exception: MsalException) {
            throw classifySilentAuthException(exception)
        }
    }

    override suspend fun invalidate() {
        val app = awaitApplication()
        suspendCancellableCoroutine { continuation ->
            app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    _state.value = AuthState.SignedOut
                    continuation.resume(Unit)
                }

                override fun onError(exception: MsalException) {
                    _state.value = AuthState.SignedOut
                    continuation.resumeWithException(
                        AuthenticationRequiredException(exception)
                    )
                }
            })
        }
    }

    private suspend fun awaitApplication(): ISingleAccountPublicClientApplication {
        initialize()
        return application.await()
    }

    private suspend fun acquireTokenSilent(
        app: ISingleAccountPublicClientApplication,
        account: IAccount
    ): AccessToken = suspendCancellableCoroutine { continuation ->
        app.acquireTokenSilentAsync(
            AcquireTokenSilentParameters.Builder()
                .withScopes(SCOPES)
                .forAccount(account)
                .fromAuthority(account.authority)
                .withCallback(object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        continuation.resume(
                            AccessToken(
                                value = authenticationResult.accessToken,
                                accountId = authenticationResult.account.id
                            )
                        )
                    }

                    override fun onError(exception: MsalException) {
                        continuation.resumeWithException(exception)
                    }
                })
                .build()
        )
    }

    private suspend fun clearCurrentAccount(
        app: ISingleAccountPublicClientApplication
    ) {
        suspendCancellableCoroutine { continuation ->
            app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    _state.value = AuthState.SignedOut
                    continuation.resume(Unit)
                }

                override fun onError(exception: MsalException) {
                    continuation.resumeWithException(exception)
                }
            })
        }
    }

    private fun refreshAccount(app: ISingleAccountPublicClientApplication) {
        app.getCurrentAccountAsync(
            object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    _state.value = activeAccount?.toSignedIn() ?: AuthState.SignedOut
                }

                override fun onAccountChanged(
                    priorAccount: IAccount?,
                    currentAccount: IAccount?
                ) {
                    _state.value = currentAccount?.toSignedIn() ?: AuthState.SignedOut
                }

                override fun onError(exception: MsalException) {
                    _state.value = AuthState.Error(
                        exception.message ?: "无法读取登录状态"
                    )
                }
            }
        )
    }

    private suspend fun currentAccount(
        app: ISingleAccountPublicClientApplication
    ): IAccount? = suspendCancellableCoroutine { continuation ->
        app.getCurrentAccountAsync(
            object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    continuation.resume(activeAccount)
                }

                override fun onAccountChanged(
                    priorAccount: IAccount?,
                    currentAccount: IAccount?
                ) = Unit

                override fun onError(exception: MsalException) {
                    continuation.resumeWithException(exception)
                }
            }
        )
    }

    private fun IAccount.toSignedIn(): AuthState.SignedIn =
        AuthState.SignedIn(
            accountId = id,
            email = username.ifBlank { "已登录账户" }
        )

    companion object {
        val SCOPES = listOf(
            "api://dc183072-2c27-4ad0-a6a8-3df3b91de4ad/sync.readwrite"
        )
    }
}

internal fun classifySilentAuthException(exception: MsalException): Throwable =
    when {
        exception is MsalClientException &&
            exception.errorCode in setOf(
                MsalClientException.DEVICE_NETWORK_NOT_AVAILABLE,
                MsalClientException.IO_ERROR
            ) -> RetryableAuthenticationException(exception)
        exception is MsalServiceException &&
            (
                exception.httpStatusCode >= 500 ||
                    exception.errorCode in setOf(
                        MsalServiceException.SERVICE_NOT_AVAILABLE,
                        MsalServiceException.REQUEST_TIMEOUT
                    )
                ) -> RetryableAuthenticationException(exception)
        else -> exception
    }
