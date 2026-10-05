package org.lepotager.resiliencevault.cloud

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.lepotager.resiliencevault.panic.RemoteDeleteAttempt
import org.lepotager.resiliencevault.panic.RemoteDeleteIntent
import org.lepotager.resiliencevault.panic.RemoteDeleteRetryReason

class IntentBoundRemoteDeleteEffectTest {
    private val intent = RemoteDeleteIntent(
        capsuleFormatVersion = 1,
        capsuleIdHex = "1".repeat(32),
        tenantId = "tenant-1",
        vaultIdHex = "2".repeat(64),
        vaultGenerationHex = "3".repeat(64),
        serviceId = "primary",
        capsuleSha256Hex = "4".repeat(64),
    )

    @Test
    fun forwards_exact_persisted_intent_and_releases_credential_after_attempt() = runTest {
        val credential = DeleteOnlyCredential.fromBytes(ByteArray(32) { 7 })
        var observedIntent: RemoteDeleteIntent? = null
        var observedCredential: DeleteOnlyCredential? = null
        val effect = IntentBoundRemoteDeleteEffect(
            capsules = DeleteOnlyCapsuleReader { requested ->
                assertEquals(intent, requested)
                credential
            },
            remote = object : RemoteDeletePort {
                override suspend fun deleteGeneration(
                    intent: RemoteDeleteIntent,
                    credential: DeleteOnlyCredential,
                ): RemoteDeleteAttempt {
                    observedIntent = intent
                    observedCredential = credential
                    credential.copyToken()
                    return RemoteDeleteAttempt.TombstonedPending
                }
            },
        )

        assertEquals(RemoteDeleteAttempt.TombstonedPending, effect.delete(intent))
        assertEquals(intent, observedIntent)
        assertEquals(credential, observedCredential)
        assertThrows(IllegalStateException::class.java) {
            credential.copyToken()
        }
    }

    @Test
    fun unavailable_backend_is_retryable_and_never_complete() = runTest {
        val credential = DeleteOnlyCredential.fromBytes(ByteArray(32) { 9 })
        val result = UnavailableRemoteDeletePort.deleteGeneration(intent, credential)
        credential.close()

        assertEquals(
            RemoteDeleteAttempt.RetryableFailure(RemoteDeleteRetryReason.ADAPTER_UNAVAILABLE),
            result,
        )
    }
}
