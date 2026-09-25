package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.protobuf.ByteString
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotKekAeadTest {
    private val aad = byteArrayOf(1, 3, 3, 7)

    @Test
    fun tink123SerializationConsumesExactlyOneEncryptAndDecryptsExactCiphertext() = runTest {
        TinkVaultSession.register()
        val keyset = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val kek = softwareKek()

        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, kek)
        }
        val encrypt = OneShotKekAead.encrypting(
            encryptCipher,
            aad,
            encryptCipher.iv.copyOf(),
            currentCoroutineContext(),
        )

        val serialized = TinkProtoKeysetFormat.serializeEncryptedKeyset(
            keyset,
            encrypt,
            aad,
            RegistryConfiguration.get(),
        )
        assertTrue(encrypt.wasConsumedExactlyOnce())

        val parsed = parseStrictEncryptedKeyset(serialized)
        assertArrayEquals(encrypt.producedEnvelope(), parsed.kekEnvelope)
        assertThrows(GeneralSecurityException::class.java) {
            encrypt.encrypt(byteArrayOf(9), aad)
        }

        val iv = parsed.kekEnvelope.copyOfRange(1, 13)
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, iv))
        }
        val decrypt = OneShotKekAead.decrypting(
            decryptCipher,
            aad,
            parsed.kekEnvelope,
            currentCoroutineContext(),
        )
        val reopened = TinkProtoKeysetFormat.parseEncryptedKeyset(
            serialized,
            decrypt,
            aad,
            RegistryConfiguration.get(),
        )

        assertTrue(decrypt.wasConsumedExactlyOnce())
        assertTrue(keyset.equalsKeyset(reopened))
        assertThrows(GeneralSecurityException::class.java) {
            decrypt.decrypt(parsed.kekEnvelope, aad)
        }
    }

    @Test
    fun wrongDirectionAadAndCiphertextConsumeAdapterFailClosed() = runTest {
        val key = softwareKek()
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val wrongDirection = OneShotKekAead.encrypting(
            encryptCipher,
            aad,
            encryptCipher.iv.copyOf(),
            currentCoroutineContext(),
        )
        assertThrows(GeneralSecurityException::class.java) {
            wrongDirection.decrypt(ByteArray(29), aad)
        }
        assertThrows(GeneralSecurityException::class.java) {
            wrongDirection.encrypt(byteArrayOf(1), aad)
        }

        val aadCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val wrongAad = OneShotKekAead.encrypting(
            aadCipher,
            aad,
            aadCipher.iv.copyOf(),
            currentCoroutineContext(),
        )
        assertThrows(GeneralSecurityException::class.java) {
            wrongAad.encrypt(byteArrayOf(1), byteArrayOf(4))
        }
        assertThrows(GeneralSecurityException::class.java) {
            wrongAad.encrypt(byteArrayOf(1), aad)
        }

        val expected = encryptRawEnvelope(key, byteArrayOf(8), aad)
        val iv = expected.copyOfRange(1, 13)
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val exactOnly = OneShotKekAead.decrypting(
            decryptCipher,
            aad,
            expected,
            currentCoroutineContext(),
        )
        val altered = expected.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThrows(GeneralSecurityException::class.java) {
            exactOnly.decrypt(altered, aad)
        }
        assertThrows(GeneralSecurityException::class.java) {
            exactOnly.decrypt(expected, aad)
        }
    }

    @Test
    fun canceledContextConsumesCipherWithoutPublishingEnvelope() {
        val key = softwareKek()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val cancelled = Job().apply { cancel() }
        val oneShot = OneShotKekAead.encrypting(
            cipher,
            aad,
            cipher.iv.copyOf(),
            cancelled,
        )

        assertThrows(CancellationException::class.java) {
            oneShot.encrypt(byteArrayOf(1, 2, 3), aad)
        }
        assertTrue(oneShot.wasConsumedExactlyOnce())
        assertTrue(oneShot.producedEnvelope() == null)
        assertThrows(GeneralSecurityException::class.java) {
            oneShot.encrypt(byteArrayOf(1), aad)
        }
    }

    @Test
    fun strictEncryptedKeysetParserRejectsUnknownDuplicateAndWrongVersion() = runTest {
        TinkVaultSession.register()
        val keyset = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val key = softwareKek()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val oneShot = OneShotKekAead.encrypting(
            cipher,
            aad,
            cipher.iv.copyOf(),
            currentCoroutineContext(),
        )
        val serialized = TinkProtoKeysetFormat.serializeEncryptedKeyset(
            keyset,
            oneShot,
            aad,
            RegistryConfiguration.get(),
        )
        val parsed = parseStrictEncryptedKeyset(serialized)

        assertThrows(Exception::class.java) {
            parseStrictEncryptedKeyset(serialized + byteArrayOf(0x78, 0x01))
        }

        val duplicateEncrypted = byteArrayOf(0x12) +
            encodeVarint(parsed.kekEnvelope.size) +
            parsed.kekEnvelope
        assertThrows(Exception::class.java) {
            parseStrictEncryptedKeyset(serialized + duplicateEncrypted)
        }

        val wrongVersion = parsed.kekEnvelope.copyOf().also { it[0] = 2 }
        val wrongProto = parsed.proto.toBuilder()
            .setEncryptedKeyset(ByteString.copyFrom(wrongVersion))
            .build()
            .toByteArray()
        assertThrows(Exception::class.java) {
            parseStrictEncryptedKeyset(wrongProto)
        }
    }

    private fun softwareKek(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun encryptRawEnvelope(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad)
        }
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    }

    private fun encodeVarint(value: Int): ByteArray {
        var remaining = value
        val result = ArrayList<Byte>()
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            result += next.toByte()
        } while (remaining != 0)
        return result.toByteArray()
    }
}
