package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import org.junit.Assert.assertTrue

internal class TestLocalKekEnvelope(private val aead: Aead) : LocalKekEnvelope {
    override suspend fun encryptKeyset(handle: KeysetHandle, aad: ByteArray): ByteArray =
        TinkProtoKeysetFormat.serializeEncryptedKeyset(
            handle, aead, aad, RegistryConfiguration.get()
        )

    override suspend fun decryptKeyset(envelope: ByteArray, aad: ByteArray): KeysetHandle =
        TinkProtoKeysetFormat.parseEncryptedKeyset(
            envelope, aead, aad, RegistryConfiguration.get()
        )
}

internal suspend fun assertSuspendFails(block: suspend () -> Unit) {
    var failed = false
    try {
        block()
    } catch (_: Exception) {
        failed = true
    }
    assertTrue("Expected suspend operation to fail", failed)
}
