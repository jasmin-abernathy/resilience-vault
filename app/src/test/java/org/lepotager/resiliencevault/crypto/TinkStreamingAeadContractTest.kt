package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test

/**
 * Contract tests for the primitive selected by CRYPTO-FORMAT-AND-KEY-LIFECYCLE.md.
 *
 * These tests intentionally use an ephemeral in-memory keyset. They do NOT implement or validate
 * production key storage, Android Keystore wrapping, provisioning, recovery, rotation or panic.
 */
class TinkStreamingAeadContractTest {
    private val streamingAead: StreamingAead by lazy {
        val handle = KeysetHandle.generateNew(
            PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB
        )
        handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private val aad = VaultBinding(
        purpose = VaultBinding.Purpose.OBJECT_DATA,
        vaultIdHex = "01".repeat(32),
        generationHex = "02".repeat(32),
        objectIdHex = "03".repeat(32),
        keyEpoch = 1L,
        revision = 1L
    ).associatedData()

    private fun encrypt(plaintext: ByteArray, associatedData: ByteArray = aad): ByteArray {
        val ciphertext = ByteArrayOutputStream()
        streamingAead.newEncryptingStream(ciphertext, associatedData).use { stream ->
            stream.write(plaintext)
        }
        return ciphertext.toByteArray()
    }

    private fun decrypt(ciphertext: ByteArray, associatedData: ByteArray = aad): ByteArray =
        streamingAead.newDecryptingStream(
            ByteArrayInputStream(ciphertext),
            associatedData
        ).use { it.readBytes() }

    @Test
    fun round_trip_crosses_one_megabyte_segment_boundary() {
        val plaintext = ByteArray(1024 * 1024 + 37) { index ->
            ((index * 31) and 0xff).toByte()
        }

        assertArrayEquals(plaintext, decrypt(encrypt(plaintext)))
    }

    @Test
    fun same_plaintext_is_not_deterministic() {
        val plaintext = "same plaintext, fresh streaming header".toByteArray()

        assertFalse(encrypt(plaintext).contentEquals(encrypt(plaintext)))
    }

    @Test
    fun modified_aad_is_rejected() {
        val ciphertext = encrypt("bound to VaultBinding".toByteArray())
        val wrongAad = aad.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }

        assertThrows(Exception::class.java) {
            decrypt(ciphertext, wrongAad)
        }
    }

    @Test
    fun modified_ciphertext_is_rejected() {
        val ciphertext = encrypt("authenticated ciphertext".toByteArray()).also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }

        assertThrows(Exception::class.java) {
            decrypt(ciphertext)
        }
    }

    @Test
    fun truncated_ciphertext_is_rejected() {
        val ciphertext = encrypt(ByteArray(8192) { 0x5a.toByte() })
        val truncated = ciphertext.copyOf(ciphertext.size - 1)

        assertThrows(Exception::class.java) {
            decrypt(truncated)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun registerTink() {
            StreamingAeadConfig.register()
        }
    }
}
