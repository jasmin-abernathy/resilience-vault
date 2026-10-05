package org.lepotager.resiliencevault.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Candidate V1 installation registry. It is intentionally NOT wired into provisioning or panic.
 * GPT-6 must review its transaction/migration semantics before activation.
 *
 * The digest detects accidental corruption only. It does not authenticate against an attacker
 * able to roll back or replace application-private files.
 */
data class VaultSecurityRegistryRecord(
    val vaultIdHex: String,
    val generationHex: String,
    val revision: Long,
    val aliases: List<String>,
) {
    init {
        require(vaultIdHex.isRegistryId() && generationHex.isRegistryId())
        require(revision > 0)
        require(aliases.size <= VaultSecurityRegistryCodec.MAX_ALIASES)
        require(aliases == aliases.distinct().sorted()) {
            "Aliases must be unique and canonically sorted"
        }
        aliases.forEach { require(it.isRegistryAlias()) { "Invalid Keystore alias" } }
    }
}

private fun String.isRegistryId(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.isRegistryAlias(): Boolean =
    length in 4..VaultSecurityRegistryCodec.MAX_ALIAS_BYTES &&
        startsWith("rv.") && all { it in 'a'..'z' || it in '0'..'9' || it in ".-_" }

object VaultSecurityRegistryCodec {
    private val magic = byteArrayOf(0x52, 0x56, 0x53, 0x31) // RVS1
    const val MAX_ALIASES = 32
    const val MAX_ALIAS_BYTES = 192
    private const val DIGEST_BYTES = 32
    private const val FIXED_DATA_BYTES = 4 + 32 + 32 + 8 + 1
    const val MAX_FILE_BYTES = FIXED_DATA_BYTES + MAX_ALIASES * (2 + MAX_ALIAS_BYTES) + DIGEST_BYTES

    fun encode(record: VaultSecurityRegistryRecord): ByteArray {
        val data = ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.write(magic)
                out.write(record.vaultIdHex.registryHexBytes())
                out.write(record.generationHex.registryHexBytes())
                out.writeLong(record.revision)
                out.writeByte(record.aliases.size)
                record.aliases.forEach { alias ->
                    val bytes = alias.toByteArray(StandardCharsets.US_ASCII)
                    out.writeShort(bytes.size)
                    out.write(bytes)
                }
            }
            raw.toByteArray()
        }
        check(data.size + DIGEST_BYTES <= MAX_FILE_BYTES)
        return data + MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun decode(bytes: ByteArray): VaultSecurityRegistryRecord {
        require(bytes.size in (FIXED_DATA_BYTES + DIGEST_BYTES)..MAX_FILE_BYTES) {
            "Invalid registry length"
        }
        val data = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(data)
        require(MessageDigest.isEqual(
            expectedDigest,
            bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        )) { "Corrupt registry" }

        return DataInputStream(ByteArrayInputStream(data)).use { input ->
            val prefix = ByteArray(4).also { input.readFully(it) }
            require(prefix.contentEquals(magic)) { "Unknown registry version" }
            val vaultId = ByteArray(32).also { input.readFully(it) }.registryHex()
            val generation = ByteArray(32).also { input.readFully(it) }.registryHex()
            val revision = input.readLong()
            val aliasCount = input.readUnsignedByte()
            require(aliasCount <= MAX_ALIASES) { "Too many aliases" }
            val aliases = ArrayList<String>(aliasCount)
            repeat(aliasCount) {
                val length = input.readUnsignedShort()
                require(length in 4..MAX_ALIAS_BYTES) { "Invalid alias length" }
                val aliasBytes = ByteArray(length).also { input.readFully(it) }
                require(aliasBytes.all { byte -> (byte.toInt() and 0xff) in 0x20..0x7e }) {
                    "Non-ASCII alias"
                }
                aliases += String(aliasBytes, StandardCharsets.US_ASCII)
            }
            require(input.available() == 0) { "Trailing registry bytes" }
            VaultSecurityRegistryRecord(vaultId, generation, revision, aliases)
        }
    }

    private fun String.registryHexBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.registryHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
