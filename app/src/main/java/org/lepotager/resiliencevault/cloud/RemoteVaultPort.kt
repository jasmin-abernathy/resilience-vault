package org.lepotager.resiliencevault.cloud

import java.io.InputStream

@JvmInline value class RemoteObjectId(val value: String)

sealed interface RemoteDeleteResult {
    data object Deleted : RemoteDeleteResult
    data object NoNetwork : RemoteDeleteResult
    data class Failed(val retryable: Boolean) : RemoteDeleteResult
}

interface RemoteVaultPort {
    suspend fun uploadCiphertext(objectId: RemoteObjectId, ciphertext: InputStream)
    suspend fun deleteEntireVault(credential: DeleteOnlyCredential): RemoteDeleteResult
}
