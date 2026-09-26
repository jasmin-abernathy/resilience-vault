package org.lepotager.resiliencevault.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.lepotager.resiliencevault.panic.RemoteDeleteConfiguration
import org.lepotager.resiliencevault.panic.RemoteDeleteIntent

enum class ActiveVaultAuthorityOrigin {
    LEGACY_ADOPTED,
    FRESH_PROVISIONED,
}

enum class ActiveVaultAuthorityStatus {
    READY,
    BLOCKED_TRANSITION,
}

data class ActiveVaultAuthorityRecord(
    val vaultIdHex: String,
    val vaultGenerationHex: String,
    val authorityRevision: Long,
    val mode: RemoteDeleteConfiguration,
    val intent: RemoteDeleteIntent?,
    val origin: ActiveVaultAuthorityOrigin,
    val status: ActiveVaultAuthorityStatus,
) {
    init {
        require(isId(vaultIdHex) && isId(vaultGenerationHex))
        require(authorityRevision > 0)
        when (mode) {
            RemoteDeleteConfiguration.UNKNOWN,
            RemoteDeleteConfiguration.NOT_CONFIGURED -> require(intent == null)
            RemoteDeleteConfiguration.CONFIGURED -> {
                val bound = requireNotNull(intent)
                require(bound.vaultIdHex == vaultIdHex)
                require(bound.vaultGenerationHex == vaultGenerationHex)
            }
        }
    }

    companion object {
        fun adoptedUnknown(vaultIdHex: String, generationHex: String) =
            ActiveVaultAuthorityRecord(
                vaultIdHex = vaultIdHex,
                vaultGenerationHex = generationHex,
                authorityRevision = 1,
                mode = RemoteDeleteConfiguration.UNKNOWN,
                intent = null,
                origin = ActiveVaultAuthorityOrigin.LEGACY_ADOPTED,
                status = ActiveVaultAuthorityStatus.READY,
            )

        fun freshNotConfigured(vaultIdHex: String, generationHex: String) =
            ActiveVaultAuthorityRecord(
                vaultIdHex = vaultIdHex,
                vaultGenerationHex = generationHex,
                authorityRevision = 1,
                mode = RemoteDeleteConfiguration.NOT_CONFIGURED,
                intent = null,
                origin = ActiveVaultAuthorityOrigin.FRESH_PROVISIONED,
                status = ActiveVaultAuthorityStatus.READY,
            )

        private fun isId(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

internal object ActiveVaultAuthorityCodec {
    private const val MAGIC = 0x52564131 // RVA1
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val MAX_TEXT_BYTES = 256
    const val MAX_FILE_BYTES = 4 * 1024

    fun encode(record: ActiveVaultAuthorityRecord): ByteArray {
        val payload = ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                writeAscii(out, record.vaultIdHex)
                writeAscii(out, record.vaultGenerationHex)
                out.writeLong(record.authorityRevision)
                out.writeUTF(record.mode.name)
                out.writeUTF(record.origin.name)
                out.writeUTF(record.status.name)
                out.writeBoolean(record.intent != null)
                record.intent?.let { writeIntent(out, it) }
            }
            raw.toByteArray()
        }
        require(payload.size + DIGEST_BYTES <= MAX_FILE_BYTES)
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(bytes: ByteArray): ActiveVaultAuthorityRecord {
        require(bytes.size in (DIGEST_BYTES + 16)..MAX_FILE_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val digest = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        require(
            MessageDigest.isEqual(
                digest,
                MessageDigest.getInstance("SHA-256").digest(payload),
            )
        ) { "Corrupt active vault authority" }

        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Unknown active authority magic" }
            require(input.readInt() == VERSION) { "Unknown active authority version" }
            val vaultId = readAscii(input, 64)
            val generation = readAscii(input, 64)
            val revision = input.readLong()
            val mode = RemoteDeleteConfiguration.valueOf(input.readUTF())
            val origin = ActiveVaultAuthorityOrigin.valueOf(input.readUTF())
            val status = ActiveVaultAuthorityStatus.valueOf(input.readUTF())
            val intent = if (input.readBoolean()) readIntent(input) else null
            require(input.available() == 0) { "Trailing active authority bytes" }
            ActiveVaultAuthorityRecord(
                vaultIdHex = vaultId,
                vaultGenerationHex = generation,
                authorityRevision = revision,
                mode = mode,
                intent = intent,
                origin = origin,
                status = status,
            )
        }
    }

    private fun writeIntent(out: DataOutputStream, intent: RemoteDeleteIntent) {
        out.writeInt(intent.capsuleFormatVersion)
        writeAscii(out, intent.capsuleIdHex)
        writeUtf8(out, intent.tenantId)
        writeAscii(out, intent.vaultIdHex)
        writeAscii(out, intent.vaultGenerationHex)
        writeUtf8(out, intent.serviceId)
        writeAscii(out, intent.capsuleSha256Hex)
    }

    private fun readIntent(input: DataInputStream): RemoteDeleteIntent =
        RemoteDeleteIntent(
            capsuleFormatVersion = input.readInt(),
            capsuleIdHex = readAscii(input, 64),
            tenantId = readUtf8(input, 128),
            vaultIdHex = readAscii(input, 64),
            vaultGenerationHex = readAscii(input, 64),
            serviceId = readUtf8(input, 64),
            capsuleSha256Hex = readAscii(input, 64),
        )

    private fun writeAscii(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size in 1..MAX_TEXT_BYTES)
        require(String(bytes, StandardCharsets.US_ASCII) == value)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readAscii(input: DataInputStream, max: Int): String {
        val size = input.readUnsignedShort()
        require(size in 1..max)
        val bytes = ByteArray(size).also(input::readFully)
        require(bytes.all { (it.toInt() and 0xff) in 0x21..0x7e })
        return String(bytes, StandardCharsets.US_ASCII)
    }

    private fun writeUtf8(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..MAX_TEXT_BYTES)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readUtf8(input: DataInputStream, max: Int): String {
        val size = input.readUnsignedShort()
        require(size in 1..max)
        val bytes = ByteArray(size).also(input::readFully)
        val value = String(bytes, StandardCharsets.UTF_8)
        require(value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes))
        return value
    }
}

sealed interface ActiveVaultAuthorityRead {
    data object Missing : ActiveVaultAuthorityRead
    data class Ready(val record: ActiveVaultAuthorityRecord) : ActiveVaultAuthorityRead
    data object Unavailable : ActiveVaultAuthorityRead
}

sealed interface ActiveVaultAuthorityResolution {
    data object Missing : ActiveVaultAuthorityResolution
    data object Unavailable : ActiveVaultAuthorityResolution
    data class Ready(val record: ActiveVaultAuthorityRecord) : ActiveVaultAuthorityResolution
    data class BlockedTransition(val record: ActiveVaultAuthorityRecord) : ActiveVaultAuthorityResolution
}

internal object ActiveVaultAuthorityResolver {
    fun resolve(
        authority: ActiveVaultAuthorityRead,
        registry: VaultSecurityRegistryRead,
    ): ActiveVaultAuthorityResolution =
        when (authority) {
            ActiveVaultAuthorityRead.Missing -> ActiveVaultAuthorityResolution.Missing
            ActiveVaultAuthorityRead.Unavailable -> ActiveVaultAuthorityResolution.Unavailable
            is ActiveVaultAuthorityRead.Ready -> {
                val record = authority.record
                val crypto = (registry as? VaultSecurityRegistryRead.Ready)?.record
                    ?: return ActiveVaultAuthorityResolution.BlockedTransition(record)
                if (
                    record.status != ActiveVaultAuthorityStatus.READY ||
                    crypto.vaultIdHex != record.vaultIdHex ||
                    crypto.generationHex != record.vaultGenerationHex
                ) {
                    ActiveVaultAuthorityResolution.BlockedTransition(record)
                } else {
                    ActiveVaultAuthorityResolution.Ready(record)
                }
            }
        }
}
