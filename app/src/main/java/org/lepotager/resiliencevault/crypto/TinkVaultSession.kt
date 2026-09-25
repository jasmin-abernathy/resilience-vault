package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Internal integration core, deliberately not reachable from the production UI. */
internal class TinkVaultSession private constructor(
    val vaultId: String,
    val generation: String,
    val epoch: Long,
    keyset: KeysetHandle,
) : AutoCloseable {
    private val key = AtomicReference<KeysetHandle?>(keyset)

    init { context(VaultBinding.Purpose.EPOCH_LOCAL).associatedData() }

    fun context(purpose: VaultBinding.Purpose, objectId: String = ZERO_ID, revision: Long = 0) =
        VaultBinding(purpose, vaultId, generation, objectId, epoch, revision)

    private fun epochKey(): KeysetHandle = key.get()
        ?: throw GeneralSecurityException("Vault handle invalidated")

    internal fun checkLive() { epochKey() }

    override fun close() { key.set(null) } // Tink/JVM does not promise physical RAM zeroization.
    override fun toString() = "TinkVaultSession[redacted]"

    private fun validate(binding: VaultBinding, purpose: VaultBinding.Purpose) {
        require(binding.vaultIdHex == vaultId && binding.generationHex == generation &&
            binding.keyEpoch == epoch && binding.purpose == purpose)
        require(binding.objectIdHex != ZERO_ID && binding.revision > 0)
        binding.associatedData()
        checkLive()
    }

    suspend fun wrapLocal(kek: LocalKekEnvelope): ByteArray {
        currentCoroutineContext().ensureActive()
        val aad = context(VaultBinding.Purpose.EPOCH_LOCAL).associatedData()
        val sealed = kek.encryptKeyset(epochKey(), aad)
        currentCoroutineContext().ensureActive()
        check(sealed.size <= MAX_KEYSET_BYTES)
        checkLive()
        return aad + sealed
    }

    internal fun wrapRecovery(recovery: KeysetHandle, head: String): ByteArray =
        wrapEpoch(recovery.aead(), recoveryAad(vaultId, generation, epoch, head))

    private fun wrapEpoch(wrapper: Aead, aad: ByteArray): ByteArray {
        val sealed = TinkProtoKeysetFormat.serializeEncryptedKeyset(epochKey(), wrapper, aad, RegistryConfiguration.get())
        check(sealed.size <= MAX_KEYSET_BYTES)
        checkLive()
        return aad + sealed
    }

    fun encryptObject(binding: VaultBinding, plaintext: ByteArray): ByteArray {
        validate(binding, VaultBinding.Purpose.OBJECT_DATA)
        require(plaintext.size <= MAX_OBJECT_BYTES)
        val objectKey = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)
        val wrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(
            objectKey, epochKey().aead(), binding.copy(purpose = VaultBinding.Purpose.OBJECT_KEY).associatedData(),
            RegistryConfiguration.get()
        )
        check(wrapped.size <= MAX_KEYSET_BYTES)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply {
            write(binding.associatedData()); writeInt(wrapped.size); write(wrapped)
        }
        objectKey.streaming().newEncryptingStream(output, binding.associatedData()).use { stream ->
            var offset = 0
            while (offset < plaintext.size) {
                checkLive()
                val count = minOf(8192, plaintext.size - offset)
                stream.write(plaintext, offset, count)
                offset += count
            }
        }
        checkLive()
        return output.toByteArray()
    }

    /** Returns only after final segment authentication and EOF; no caller-visible partial plaintext. */
    fun decryptObject(binding: VaultBinding, ciphertext: ByteArray): ByteArray {
        validate(binding, VaultBinding.Purpose.OBJECT_DATA)
        require(ciphertext.size <= MAX_CONTAINER_BYTES)
        val input = DataInputStream(ByteArrayInputStream(ciphertext))
        input.use {
            val aad = ByteArray(120).also(input::readFully)
            require(MessageDigest.isEqual(aad, binding.associatedData())) { "Unexpected object context" }
            val size = input.readInt()
            require(size in 1..MAX_KEYSET_BYTES)
            val wrapped = ByteArray(size).also(input::readFully)
            val objectKey = TinkProtoKeysetFormat.parseEncryptedKeyset(
                wrapped, epochKey().aead(), binding.copy(purpose = VaultBinding.Purpose.OBJECT_KEY).associatedData(),
                RegistryConfiguration.get()
            )
            requireKeyType(objectKey, streaming = true)
            // Fixed bounded buffer can be wiped even on failed authentication.
            val buffer = ByteArray(MAX_OBJECT_BYTES + 1)
            try {
                var count = 0
                objectKey.streaming().newDecryptingStream(input, aad).use { stream ->
                    while (true) {
                        checkLive()
                        check(count <= MAX_OBJECT_BYTES) { "Object exceeds limit" }
                        val read = stream.read(buffer, count, minOf(8192, buffer.size - count))
                        if (read < 0) break
                        check(read > 0) { "Non-progressing input" }
                        count += read
                    }
                }
                checkLive()
                return buffer.copyOf(count)
            } finally { buffer.fill(0) }
        }
    }

    fun sealManifest(binding: VaultBinding, plaintext: ByteArray): ByteArray {
        validate(binding, VaultBinding.Purpose.MANIFEST)
        require(plaintext.size <= MAX_MANIFEST_BYTES)
        val aad = binding.associatedData()
        val result = aad + epochKey().aead().encrypt(plaintext, aad)
        checkLive()
        return result
    }

    fun openManifest(binding: VaultBinding, container: ByteArray): ByteArray {
        validate(binding, VaultBinding.Purpose.MANIFEST)
        require(container.size in 149..(MAX_MANIFEST_BYTES + 256))
        val aad = binding.associatedData()
        require(MessageDigest.isEqual(container.copyOfRange(0, 120), aad))
        val plain = epochKey().aead().decrypt(container.copyOfRange(120, container.size), aad)
        try { require(plain.size <= MAX_MANIFEST_BYTES); checkLive(); return plain.copyOf() }
        finally { plain.fill(0) }
    }

    companion object {
        const val ZERO_ID = "0000000000000000000000000000000000000000000000000000000000000000"
        const val MAX_KEYSET_BYTES = 65536
        const val MAX_OBJECT_BYTES = 16 * 1024 * 1024
        const val MAX_CONTAINER_BYTES = MAX_OBJECT_BYTES + 128 * 1024
        const val MAX_MANIFEST_BYTES = 1024 * 1024

        fun register() { AeadConfig.register(); StreamingAeadConfig.register() }

        fun create(vaultId: String, generation: String, epoch: Long): TinkVaultSession {
            register()
            return TinkVaultSession(vaultId, generation, epoch,
                KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM))
        }

        suspend fun openLocal(
            vaultId: String,
            generation: String,
            epoch: Long,
            envelope: ByteArray,
            kek: LocalKekEnvelope,
        ): TinkVaultSession {
            register()
            currentCoroutineContext().ensureActive()
            val aad = VaultBinding(
                VaultBinding.Purpose.EPOCH_LOCAL,
                vaultId,
                generation,
                ZERO_ID,
                epoch,
                0,
            ).associatedData()
            require(envelope.size in (aad.size + 1)..(aad.size + MAX_KEYSET_BYTES))
            require(MessageDigest.isEqual(envelope.copyOfRange(0, aad.size), aad))
            val handle = kek.decryptKeyset(envelope.copyOfRange(aad.size, envelope.size), aad)
            currentCoroutineContext().ensureActive()
            requireKeyType(handle, streaming = false)
            return TinkVaultSession(vaultId, generation, epoch, handle)
        }

        internal fun openRecovery(vaultId: String, generation: String, epoch: Long, head: String,
                                  envelope: ByteArray, recovery: KeysetHandle): TinkVaultSession =
            openEpoch(vaultId, generation, epoch, envelope, recovery.aead(), recoveryAad(vaultId, generation, epoch, head))

        private fun openEpoch(vaultId: String, generation: String, epoch: Long, envelope: ByteArray,
                              wrapper: Aead, aad: ByteArray): TinkVaultSession {
            register()
            require(envelope.size in (aad.size + 1)..(aad.size + MAX_KEYSET_BYTES))
            require(MessageDigest.isEqual(envelope.copyOfRange(0, aad.size), aad))
            val handle = TinkProtoKeysetFormat.parseEncryptedKeyset(
                envelope.copyOfRange(aad.size, envelope.size), wrapper, aad, RegistryConfiguration.get())
            requireKeyType(handle, streaming = false)
            return TinkVaultSession(vaultId, generation, epoch, handle)
        }

        internal fun recoveryAad(vault: String, generation: String, epoch: Long, head: String): ByteArray =
            VaultBinding(VaultBinding.Purpose.EPOCH_RECOVERY, vault, generation, ZERO_ID, epoch, 0)
                .associatedData() + canonicalIdBytes(head)

        internal fun requireKeyType(handle: KeysetHandle, streaming: Boolean) {
            require(handle.size() == 1) { "Unexpected keyset size" }
            val expected = if (streaming) PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB
                else PredefinedAeadParameters.AES256_GCM
            require(handle.primary.key.parameters == expected) { "Unexpected key parameters" }
        }

        internal fun readExternalKey(bytes: ByteArray): KeysetHandle {
            register()
            require(bytes.size in 1..MAX_KEYSET_BYTES)
            return TinkProtoKeysetFormat.parseKeyset(bytes, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
                .also { requireKeyType(it, false) }
        }

        /** R only, explicit external kit; E/D have no plaintext serialization API. */
        internal fun externalKeyBytes(key: KeysetHandle): ByteArray =
            TinkProtoKeysetFormat.serializeKeyset(key, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
    }
}

internal fun KeysetHandle.aead(): Aead = getPrimitive(RegistryConfiguration.get(), Aead::class.java)
private fun KeysetHandle.streaming(): StreamingAead = getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
internal fun canonicalIdBytes(hex: String): ByteArray {
    require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' })
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
