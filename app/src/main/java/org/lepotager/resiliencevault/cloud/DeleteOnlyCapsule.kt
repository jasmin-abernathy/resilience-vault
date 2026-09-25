package org.lepotager.resiliencevault.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.lepotager.resiliencevault.panic.RemoteDeleteIntent

internal data class DeleteOnlyCapsuleIdentity(
    val capsuleFormatVersion: Int = 1,
    val capsuleIdHex: String,
    val tenantId: String,
    val vaultIdHex: String,
    val vaultGenerationHex: String,
    val serviceId: String,
) {
    fun validate() {
        toIntent("0".repeat(64))
    }

    fun toIntent(capsuleSha256Hex: String): RemoteDeleteIntent =
        RemoteDeleteIntent(
            capsuleFormatVersion = capsuleFormatVersion,
            capsuleIdHex = capsuleIdHex,
            tenantId = tenantId,
            vaultIdHex = vaultIdHex,
            vaultGenerationHex = vaultGenerationHex,
            serviceId = serviceId,
            capsuleSha256Hex = capsuleSha256Hex,
        )

    companion object {
        fun fromIntent(intent: RemoteDeleteIntent): DeleteOnlyCapsuleIdentity =
            DeleteOnlyCapsuleIdentity(
                capsuleFormatVersion = intent.capsuleFormatVersion,
                capsuleIdHex = intent.capsuleIdHex,
                tenantId = intent.tenantId,
                vaultIdHex = intent.vaultIdHex,
                vaultGenerationHex = intent.vaultGenerationHex,
                serviceId = intent.serviceId,
            )
    }
}

class DeleteOnlyCredential private constructor(
    bytes: ByteArray,
) : AutoCloseable {
    private val token = AtomicReference<ByteArray?>(bytes.copyOf())

    init {
        require(bytes.size == TOKEN_BYTES)
    }

    internal fun copyToken(): ByteArray =
        checkNotNull(token.get()) { "Delete-only credential already released" }.copyOf()

    override fun close() {
        token.getAndSet(null)?.fill(0)
    }

    override fun toString(): String = "DeleteOnlyCredential([redacted])"

    companion object {
        const val TOKEN_BYTES = 32

        internal fun fromBytes(bytes: ByteArray): DeleteOnlyCredential =
            DeleteOnlyCredential(bytes)
    }
}

/**
 * Binary format fixed by the Panic V2 security decision.
 *
 * Container:
 * magic[4] | version[1] | iv[12] | ciphertextLength[4] | ciphertext | gcmTag[16]
 *
 * Plaintext contains only the 32-byte capability and the exact identity tuple.
 */
internal object DeleteOnlyCapsuleCodec {
    private val CONTAINER_MAGIC = byteArrayOf(0x52, 0x56, 0x44, 0x31) // RVD1
    private val PLAINTEXT_MAGIC = byteArrayOf(0x52, 0x56, 0x50, 0x31) // RVP1
    private val AAD_DOMAIN = "RV-DELETE-CAPSULE-V1".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val MAX_CIPHERTEXT_BYTES = 2048
    const val MAX_CONTAINER_BYTES = 4 + 1 + IV_BYTES + 4 + MAX_CIPHERTEXT_BYTES + TAG_BYTES

    fun seal(
        identity: DeleteOnlyCapsuleIdentity,
        credential: DeleteOnlyCredential,
        key: SecretKey,
    ): ByteArray {
        identity.validate()
        val plaintext = encodePlaintext(identity, credential)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Android Keystore with randomized encryption required must own IV generation.
            // Passing a caller-generated IV can be rejected even though software AES accepts it.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv.copyOf()
            require(iv.size == IV_BYTES) { "Unexpected delete-only KEK IV length" }
            cipher.updateAAD(aad(identity))
            val sealed = cipher.doFinal(plaintext)
            require(sealed.size > TAG_BYTES)
            val ciphertextLength = sealed.size - TAG_BYTES
            require(ciphertextLength in 1..MAX_CIPHERTEXT_BYTES)

            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { out ->
                out.write(CONTAINER_MAGIC)
                out.writeByte(VERSION.toInt())
                out.write(iv)
                out.writeInt(ciphertextLength)
                out.write(sealed)
            }
            return output.toByteArray().also {
                require(it.size <= MAX_CONTAINER_BYTES)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun open(
        intent: RemoteDeleteIntent,
        container: ByteArray,
        key: SecretKey,
    ): DeleteOnlyCredential {
        require(container.size in (4 + 1 + IV_BYTES + 4 + TAG_BYTES + 1)..MAX_CONTAINER_BYTES)
        require(
            MessageDigest.isEqual(
                sha256(container),
                hexToBytes(intent.capsuleSha256Hex),
            )
        ) { "Delete-only capsule hash mismatch" }

        val identity = DeleteOnlyCapsuleIdentity.fromIntent(intent).also { it.validate() }
        val parsed = parseContainer(container)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, parsed.iv))
        cipher.updateAAD(aad(identity))
        val plaintext = cipher.doFinal(parsed.sealed)
        try {
            return decodePlaintext(plaintext, identity)
        } finally {
            plaintext.fill(0)
        }
    }

    fun sha256Hex(container: ByteArray): String =
        sha256(container).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class ParsedContainer(
        val iv: ByteArray,
        val sealed: ByteArray,
    )

    private fun parseContainer(container: ByteArray): ParsedContainer =
        DataInputStream(ByteArrayInputStream(container)).use { input ->
            val magic = ByteArray(CONTAINER_MAGIC.size).also(input::readFully)
            require(magic.contentEquals(CONTAINER_MAGIC))
            require(input.readUnsignedByte() == VERSION.toInt())
            val iv = ByteArray(IV_BYTES).also(input::readFully)
            val ciphertextLength = input.readInt()
            require(ciphertextLength in 1..MAX_CIPHERTEXT_BYTES)
            val sealedLength = Math.addExact(ciphertextLength, TAG_BYTES)
            require(sealedLength == input.available())
            val sealed = ByteArray(sealedLength).also(input::readFully)
            require(input.available() == 0)
            ParsedContainer(iv, sealed)
        }

    private fun encodePlaintext(
        identity: DeleteOnlyCapsuleIdentity,
        credential: DeleteOnlyCredential,
    ): ByteArray {
        val token = credential.copyToken()
        try {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { out ->
                out.write(PLAINTEXT_MAGIC)
                out.writeByte(VERSION.toInt())
                out.write(token)
                writeField(out, identity.capsuleIdHex)
                writeField(out, identity.tenantId)
                writeField(out, identity.vaultIdHex)
                writeField(out, identity.vaultGenerationHex)
                writeField(out, identity.serviceId)
            }
            return output.toByteArray()
        } finally {
            token.fill(0)
        }
    }

    private fun decodePlaintext(
        plaintext: ByteArray,
        expected: DeleteOnlyCapsuleIdentity,
    ): DeleteOnlyCredential =
        DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            val magic = ByteArray(PLAINTEXT_MAGIC.size).also(input::readFully)
            require(magic.contentEquals(PLAINTEXT_MAGIC))
            require(input.readUnsignedByte() == VERSION.toInt())
            val token = ByteArray(DeleteOnlyCredential.TOKEN_BYTES).also(input::readFully)
            try {
                val actual = DeleteOnlyCapsuleIdentity(
                    capsuleFormatVersion = VERSION.toInt(),
                    capsuleIdHex = readField(input, 64),
                    tenantId = readField(input, 128),
                    vaultIdHex = readField(input, 64),
                    vaultGenerationHex = readField(input, 64),
                    serviceId = readField(input, 64),
                )
                actual.validate()
                require(input.available() == 0)
                require(actual == expected) { "Delete-only capsule identity mismatch" }
                return DeleteOnlyCredential.fromBytes(token)
            } finally {
                token.fill(0)
            }
        }

    private fun aad(identity: DeleteOnlyCapsuleIdentity): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            writeBytes(out, AAD_DOMAIN)
            out.writeInt(identity.capsuleFormatVersion)
            writeField(out, identity.tenantId)
            writeField(out, identity.vaultIdHex)
            writeField(out, identity.vaultGenerationHex)
            writeField(out, identity.capsuleIdHex)
            writeField(out, identity.serviceId)
        }
        return output.toByteArray()
    }

    private fun writeField(out: DataOutputStream, value: String) =
        writeBytes(out, value.toByteArray(StandardCharsets.UTF_8))

    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        require(bytes.size in 1..512)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readField(input: DataInputStream, maxBytes: Int): String {
        val length = input.readInt()
        require(length in 1..maxBytes)
        val bytes = ByteArray(length).also(input::readFully)
        val value = bytes.toString(StandardCharsets.UTF_8)
        require(value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes))
        return value
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hexToBytes(value: String): ByteArray {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' })
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

internal interface DeleteOnlyCapsuleKeyPort {
    fun exists(identity: DeleteOnlyCapsuleIdentity): Boolean
    fun create(identity: DeleteOnlyCapsuleIdentity): SecretKey
    fun load(identity: DeleteOnlyCapsuleIdentity): SecretKey
}

internal interface DeleteOnlyCapsuleFilePort {
    fun read(capsuleIdHex: String): ByteArray?
    fun writeNew(capsuleIdHex: String, bytes: ByteArray)
}

internal class DeleteOnlyCapsuleProvisioner(
    private val keys: DeleteOnlyCapsuleKeyPort,
    private val files: DeleteOnlyCapsuleFilePort,
) {
    fun provision(
        identity: DeleteOnlyCapsuleIdentity,
        credential: DeleteOnlyCredential,
    ): RemoteDeleteIntent {
        identity.validate()
        check(!keys.exists(identity)) { "Delete-only KEK already exists" }
        check(files.read(identity.capsuleIdHex) == null) { "Delete-only capsule already exists" }

        val key = keys.create(identity)
        val bytes = DeleteOnlyCapsuleCodec.seal(identity, credential, key)
        val intent = identity.toIntent(DeleteOnlyCapsuleCodec.sha256Hex(bytes))

        files.writeNew(identity.capsuleIdHex, bytes)
        val readback = checkNotNull(files.read(identity.capsuleIdHex)) {
            "Delete-only capsule write unconfirmed"
        }
        check(readback.contentEquals(bytes)) { "Delete-only capsule readback mismatch" }

        DeleteOnlyCapsuleCodec.open(intent, readback, keys.load(identity)).use { reopened ->
            val expected = credential.copyToken()
            val actual = reopened.copyToken()
            try {
                check(MessageDigest.isEqual(expected, actual)) {
                    "Delete-only capsule credential verification failed"
                }
            } finally {
                expected.fill(0)
                actual.fill(0)
            }
        }
        return intent
    }

    fun open(intent: RemoteDeleteIntent): DeleteOnlyCredential {
        val identity = DeleteOnlyCapsuleIdentity.fromIntent(intent).also { it.validate() }
        val bytes = checkNotNull(files.read(identity.capsuleIdHex)) {
            "Referenced delete-only capsule is missing"
        }
        return DeleteOnlyCapsuleCodec.open(intent, bytes, keys.load(identity))
    }
}
