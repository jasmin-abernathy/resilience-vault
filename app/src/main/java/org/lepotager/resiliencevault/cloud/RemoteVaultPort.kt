package org.lepotager.resiliencevault.cloud

import java.io.InputStream
import org.lepotager.resiliencevault.panic.RemoteDeleteAttempt
import org.lepotager.resiliencevault.panic.RemoteDeleteIntent
import org.lepotager.resiliencevault.panic.RemoteDeleteRetryReason

@JvmInline value class RemoteObjectId(val value: String)

interface RemoteDeletePort {
    suspend fun deleteGeneration(
        intent: RemoteDeleteIntent,
        credential: DeleteOnlyCredential,
    ): RemoteDeleteAttempt
}

interface RemoteVaultPort : RemoteDeletePort {
    suspend fun uploadCiphertext(objectId: RemoteObjectId, ciphertext: InputStream)
}

/** Default until a real pinned-origin DELETE-only backend adapter exists. */
internal object UnavailableRemoteDeletePort : RemoteDeletePort {
    override suspend fun deleteGeneration(
        intent: RemoteDeleteIntent,
        credential: DeleteOnlyCredential,
    ): RemoteDeleteAttempt =
        RemoteDeleteAttempt.RetryableFailure(RemoteDeleteRetryReason.ADAPTER_UNAVAILABLE)
}

internal fun interface DeleteOnlyCapsuleReader {
    fun open(intent: RemoteDeleteIntent): DeleteOnlyCredential
}

/**
 * Opens exactly the capsule referenced by the persisted panic intent, then releases the token
 * after the one DELETE attempt. The transport must validate the pinned service origin/protocol.
 */
internal class IntentBoundRemoteDeleteEffect(
    private val capsules: DeleteOnlyCapsuleReader,
    private val remote: RemoteDeletePort,
) {
    suspend fun delete(intent: RemoteDeleteIntent): RemoteDeleteAttempt =
        capsules.open(intent).use { credential ->
            remote.deleteGeneration(intent, credential)
        }
}
