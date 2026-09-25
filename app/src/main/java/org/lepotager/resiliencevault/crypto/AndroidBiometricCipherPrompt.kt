package org.lepotager.resiliencevault.crypto

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidBiometricCipherPrompt(
    private val activity: FragmentActivity,
    private val title: CharSequence = "Autoriser l’accès au coffre",
    private val cancelLabel: CharSequence = "Annuler",
) : AuthenticatedCipherPrompt, DefaultLifecycleObserver {
    private data class Pending(
        val cipher: Cipher,
        val continuation: CancellableContinuation<Cipher>,
        val done: AtomicBoolean = AtomicBoolean(false),
    )

    private val requests = Mutex()
    private val stateLock = Any()
    @Volatile private var destroyed = false
    private var pending: Pending? = null
    private var biometricPrompt: BiometricPrompt? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    override suspend fun authenticate(cipher: Cipher): Cipher = requests.withLock {
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Main.immediate) {
            check(!destroyed) { "Biometric owner destroyed" }
            check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                "Biometric owner is not in the foreground"
            }

            val authenticators = allowedAuthenticatorsForSdk(Build.VERSION.SDK_INT)
            val status = BiometricManager.from(activity).canAuthenticate(authenticators)
            if (status != BiometricManager.BIOMETRIC_SUCCESS) {
                throw GeneralSecurityException("Required authentication unavailable ($status)")
            }

            suspendCancellableCoroutine { continuation ->
                val request = Pending(cipher, continuation)
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val returned = result.cryptoObject?.cipher
                        complete(
                            request,
                            if (returned === cipher) Result.success(cipher)
                            else Result.failure(
                                GeneralSecurityException("BiometricPrompt returned a different Cipher")
                            ),
                        )
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        complete(
                            request,
                            Result.failure(
                                GeneralSecurityException("Biometric authentication failed ($errorCode)")
                            ),
                        )
                    }

                    override fun onAuthenticationFailed() = Unit
                }

                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    callback,
                )
                val promptInfo = promptInfo(authenticators)

                synchronized(stateLock) {
                    check(pending == null) { "Concurrent biometric request" }
                    pending = request
                    biometricPrompt = prompt
                }

                continuation.invokeOnCancellation {
                    if (request.done.compareAndSet(false, true)) {
                        clear(request)
                        activity.runOnUiThread { prompt.cancelAuthentication() }
                    }
                }

                if (continuation.isActive) {
                    prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                }
            }
        }.also {
            currentCoroutineContext().ensureActive()
            check(it === cipher) { "Authenticated Cipher identity changed" }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        val (active, prompt) = synchronized(stateLock) {
            val request = pending
            val currentPrompt = biometricPrompt
            pending = null
            biometricPrompt = null
            request to currentPrompt
        }
        if (active != null && active.done.compareAndSet(false, true)) {
            prompt?.cancelAuthentication()
            active.continuation.cancel(CancellationException("Biometric owner destroyed"))
        }
        activity.lifecycle.removeObserver(this)
    }

    private fun complete(request: Pending, result: Result<Cipher>) {
        if (!request.done.compareAndSet(false, true)) return
        clear(request)
        if (request.continuation.isActive) request.continuation.resumeWith(result)
    }

    private fun clear(request: Pending) {
        synchronized(stateLock) {
            if (pending === request) {
                pending = null
                biometricPrompt = null
            }
        }
    }

    private fun promptInfo(authenticators: Int): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText(cancelLabel)
        }
        return builder.build()
    }

    internal companion object {
        fun allowedAuthenticatorsForSdk(sdk: Int): Int =
            if (sdk >= Build.VERSION_CODES.R) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            }
    }
}
