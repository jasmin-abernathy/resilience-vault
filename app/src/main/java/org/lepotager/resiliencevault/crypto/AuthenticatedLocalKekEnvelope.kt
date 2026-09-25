package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.EncryptedKeyset
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

internal fun interface AuthenticatedCipherPrompt {
    suspend fun authenticate(cipher: Cipher): Cipher
}

internal interface LocalKekEnvelope {
    suspend fun encryptKeyset(handle: KeysetHandle, aad: ByteArray): ByteArray
    suspend fun decryptKeyset(envelope: ByteArray, aad: ByteArray): KeysetHandle
}

/**
 * Bridges one already-authenticated per-use Cipher to Tink's synchronous Aead callback.
 * Tink is pinned to 1.23.0 because this relies on one encrypt callback during serialization.
 */
internal class AuthenticatedLocalKekEnvelope(
    private val loadExisting: () -> SecretKey,
    private val prompt: AuthenticatedCipherPrompt,
) : LocalKekEnvelope {
    override suspend fun encryptKeyset(handle: KeysetHandle, aad: ByteArray): ByteArray {
        require(aad.isNotEmpty())
        val key = loadExisting()
        check(key.encoded == null) { "Local KEK must be non-exportable" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv?.copyOf() ?: throw GeneralSecurityException("Missing Keystore IV")
        if (iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected Keystore IV")

        val authenticated = prompt.authenticate(cipher)
        check(authenticated === cipher) { "BiometricPrompt returned a different Cipher" }

        val context = currentCoroutineContext()
        context.ensureActive()
        val oneShot = OneShotKekAead.encrypting(cipher, aad, iv, context)
        val serialized = TinkProtoKeysetFormat.serializeEncryptedKeyset(
            handle,
            oneShot,
            aad,
            RegistryConfiguration.get(),
        )
        context.ensureActive()
        check(oneShot.wasConsumedExactlyOnce()) { "Tink did not consume KEK encrypt exactly once" }

        val parsed = parseStrictEncryptedKeyset(serialized)
        val produced = oneShot.producedEnvelope()
            ?: throw GeneralSecurityException("Missing KEK ciphertext")
        check(MessageDigest.isEqual(parsed.kekEnvelope, produced)) {
            "Tink container does not contain the produced KEK envelope"
        }
        check(parsed.proto.keysetInfo == handle.keysetInfo) { "Unexpected Tink keyset metadata" }
        check(produced.size >= MIN_ENVELOPE_BYTES && produced[0] == FORMAT_VERSION)
        check(MessageDigest.isEqual(produced.copyOfRange(1, 1 + IV_BYTES), iv))
        return serialized
    }

    override suspend fun decryptKeyset(envelope: ByteArray, aad: ByteArray): KeysetHandle {
        require(aad.isNotEmpty())
        val parsed = parseStrictEncryptedKeyset(envelope)
        val wrapped = parsed.kekEnvelope
        require(wrapped.size in MIN_ENVELOPE_BYTES..TinkVaultSession.MAX_KEYSET_BYTES)
        require(wrapped[0] == FORMAT_VERSION) { "Unknown KEK envelope version" }
        val iv = wrapped.copyOfRange(1, 1 + IV_BYTES)

        val key = loadExisting()
        check(key.encoded == null) { "Local KEK must be non-exportable" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

        val authenticated = prompt.authenticate(cipher)
        check(authenticated === cipher) { "BiometricPrompt returned a different Cipher" }

        val context = currentCoroutineContext()
        context.ensureActive()
        val oneShot = OneShotKekAead.decrypting(cipher, aad, wrapped, context)
        val handle = TinkProtoKeysetFormat.parseEncryptedKeyset(
            envelope,
            oneShot,
            aad,
            RegistryConfiguration.get(),
        )
        context.ensureActive()
        check(oneShot.wasConsumedExactlyOnce()) { "Tink did not consume KEK decrypt exactly once" }
        check(parsed.proto.keysetInfo == handle.keysetInfo) { "Unauthenticated Tink metadata mismatch" }
        return handle
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val FORMAT_VERSION: Byte = 1
        const val MIN_ENVELOPE_BYTES = 1 + IV_BYTES + 16
    }
}

internal class OneShotKekAead private constructor(
    private val mode: Mode,
    private val cipher: Cipher,
    expectedAad: ByteArray,
    private val iv: ByteArray?,
    expectedCiphertext: ByteArray?,
    private val coroutineContext: CoroutineContext,
) : Aead {
    private enum class Mode { ENCRYPT, DECRYPT }
    private val expectedAad = expectedAad.copyOf()
    private val expectedCiphertext = expectedCiphertext?.copyOf()
    private val consumed = AtomicBoolean(false)
    @Volatile private var produced: ByteArray? = null

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        consume()
        if (mode != Mode.ENCRYPT) throw GeneralSecurityException("Unexpected KEK encrypt")
        if (!MessageDigest.isEqual(associatedData, expectedAad)) {
            throw GeneralSecurityException("Unexpected KEK AAD")
        }
        require(plaintext.size in 1..TinkVaultSession.MAX_KEYSET_BYTES)
        coroutineContext.ensureActive()
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(plaintext)
        coroutineContext.ensureActive()
        val localIv = checkNotNull(iv)
        if (localIv.size != 12) throw GeneralSecurityException("Unexpected Keystore IV")
        val result = byteArrayOf(1) + localIv + encrypted
        produced = result.copyOf()
        return result
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        consume()
        if (mode != Mode.DECRYPT) throw GeneralSecurityException("Unexpected KEK decrypt")
        if (!MessageDigest.isEqual(associatedData, expectedAad)) {
            throw GeneralSecurityException("Unexpected KEK AAD")
        }
        val expected = checkNotNull(expectedCiphertext)
        if (!MessageDigest.isEqual(ciphertext, expected)) {
            throw GeneralSecurityException("Unexpected KEK ciphertext")
        }
        require(ciphertext.size in 29..TinkVaultSession.MAX_KEYSET_BYTES)
        require(ciphertext[0] == 1.toByte()) { "Unknown KEK envelope version" }
        coroutineContext.ensureActive()
        cipher.updateAAD(associatedData)
        val clear = cipher.doFinal(ciphertext, 13, ciphertext.size - 13)
        try {
            coroutineContext.ensureActive()
            return clear
        } catch (error: Exception) {
            clear.fill(0)
            throw error
        }
    }

    fun wasConsumedExactlyOnce(): Boolean = consumed.get()
    fun producedEnvelope(): ByteArray? = produced?.copyOf()

    private fun consume() {
        if (!consumed.compareAndSet(false, true)) {
            throw GeneralSecurityException("KEK adapter already consumed")
        }
    }

    companion object {
        fun encrypting(
            cipher: Cipher,
            aad: ByteArray,
            iv: ByteArray,
            coroutineContext: CoroutineContext,
        ) = OneShotKekAead(Mode.ENCRYPT, cipher, aad, iv, null, coroutineContext)

        fun decrypting(
            cipher: Cipher,
            aad: ByteArray,
            ciphertext: ByteArray,
            coroutineContext: CoroutineContext,
        ) = OneShotKekAead(Mode.DECRYPT, cipher, aad, null, ciphertext, coroutineContext)
    }
}

internal data class StrictEncryptedKeyset(
    val proto: EncryptedKeyset,
    val kekEnvelope: ByteArray,
)

internal fun parseStrictEncryptedKeyset(bytes: ByteArray): StrictEncryptedKeyset {
    require(bytes.size in 1..TinkVaultSession.MAX_KEYSET_BYTES)
    val wire = StrictEncryptedKeysetWire.parse(bytes)
    val parsed = try {
        EncryptedKeyset.parseFrom(bytes)
    } catch (error: Exception) {
        throw GeneralSecurityException("Invalid encrypted keyset container", error)
    }

    require(parsed.hasKeysetInfo()) { "Missing Tink keyset metadata" }
    require(parsed.keysetInfo.keyInfoCount == 1) { "Unexpected Tink key count" }
    require(parsed.encryptedKeyset.size() in 29..TinkVaultSession.MAX_KEYSET_BYTES)
    require(parsed.encryptedKeyset.byteAt(0) == 1.toByte()) { "Unknown KEK envelope version" }
    require(MessageDigest.isEqual(parsed.encryptedKeyset.toByteArray(), wire.kekEnvelope)) {
        "Parsed Tink ciphertext differs from strict wire ciphertext"
    }
    require(MessageDigest.isEqual(parsed.toByteArray(), bytes)) {
        "Non-canonical encrypted-keyset encoding"
    }
    return StrictEncryptedKeyset(parsed, wire.kekEnvelope)
}

/**
 * Minimal canonical wire validator for the exact Tink v1.23.0 EncryptedKeyset shape we persist.
 * It parses metadata only; encrypted key material remains opaque.
 */
private object StrictEncryptedKeysetWire {
    data class Parsed(val kekEnvelope: ByteArray)

    fun parse(bytes: ByteArray): Parsed {
        val top = Cursor(bytes)
        top.expectTag(2, 2)
        val encrypted = top.readLengthDelimited()
        require(encrypted.size in 29..TinkVaultSession.MAX_KEYSET_BYTES)
        require(encrypted[0] == 1.toByte()) { "Unknown KEK envelope version" }

        top.expectTag(3, 2)
        val keysetInfo = top.readLengthDelimited()
        top.requireDone()
        validateKeysetInfo(keysetInfo)
        return Parsed(encrypted)
    }

    private fun validateKeysetInfo(bytes: ByteArray) {
        val info = Cursor(bytes)
        info.expectTag(1, 0)
        require(info.readUInt32() != 0L) { "Missing primary key id" }
        info.expectTag(2, 2)
        validateKeyInfo(info.readLengthDelimited())
        info.requireDone()
    }

    private fun validateKeyInfo(bytes: ByteArray) {
        val info = Cursor(bytes)
        info.expectTag(1, 2)
        require(info.readLengthDelimited().isNotEmpty()) { "Missing key type URL" }
        info.expectTag(2, 0)
        require(info.readUInt32() in 1L..3L) { "Unexpected key status" }
        info.expectTag(3, 0)
        require(info.readUInt32() != 0L) { "Missing key id" }
        info.expectTag(4, 0)
        require(info.readUInt32() in 1L..4L) { "Unexpected output prefix type" }
        info.requireDone()
    }

    private class Cursor(private val bytes: ByteArray) {
        private var position = 0

        fun expectTag(field: Int, wireType: Int) {
            val expected = ((field shl 3) or wireType).toLong()
            require(readVarint() == expected) { "Unexpected protobuf field" }
        }

        fun readLengthDelimited(): ByteArray {
            val length = readUInt32().toInt()
            require(length >= 0 && position + length <= bytes.size) { "Invalid protobuf length" }
            val result = bytes.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun readUInt32(): Long = readVarint()

        fun requireDone() {
            require(position == bytes.size) { "Unexpected or duplicate protobuf field" }
        }

        private fun readVarint(): Long {
            var value = 0L
            var shift = 0
            var count = 0
            while (count < 5) {
                require(position < bytes.size) { "Truncated protobuf varint" }
                val byte = bytes[position++].toInt() and 0xff
                value = value or ((byte and 0x7f).toLong() shl shift)
                count += 1
                if ((byte and 0x80) == 0) {
                    require(count == 1 || (byte and 0x7f) != 0) { "Non-canonical protobuf varint" }
                    if (count == 5) require((byte and 0xf0) == 0) { "Protobuf uint32 overflow" }
                    return value
                }
                shift += 7
            }
            throw IllegalArgumentException("Oversized protobuf varint")
        }
    }
}

