package org.lepotager.resiliencevault.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Non-secret, bounded provisioning record. The digest detects accidental corruption, not
 * malicious rollback or tampering. A missing/corrupt record never authorizes key regeneration.
 */
data class VaultProvisioningJournal(
    val vaultIdHex: String,
    val generationHex: String,
    val epoch: Long,
    val phase: VaultProvisioningPolicy.JournalPhase
) {
    init {
        require(vaultIdHex.isCanonicalId() && generationHex.isCanonicalId())
        require(epoch > 0)
    }

    fun next(phase: VaultProvisioningPolicy.JournalPhase): VaultProvisioningJournal {
        require(phase.ordinal == this.phase.ordinal + 1) { "Provisioning phases must advance once" }
        return copy(phase = phase)
    }
}

private fun String.isCanonicalId(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

object VaultProvisioningJournalCodec {
    private val magic = byteArrayOf(0x52, 0x56, 0x4a, 0x31) // RVJ1
    const val FILE_BYTES = 4 + 32 + 32 + 8 + 1 + 32

    fun encode(record: VaultProvisioningJournal): ByteArray {
        val payload = ByteBuffer.allocate(FILE_BYTES - 32).order(ByteOrder.BIG_ENDIAN)
        payload.put(magic)
        payload.put(record.vaultIdHex.hexBytes())
        payload.put(record.generationHex.hexBytes())
        payload.putLong(record.epoch)
        payload.put(record.phase.ordinal.toByte())
        val data = payload.array()
        return data + MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun decode(bytes: ByteArray): VaultProvisioningJournal {
        require(bytes.size == FILE_BYTES) { "Invalid provisioning record length" }
        val data = bytes.copyOfRange(0, FILE_BYTES - 32)
        require(MessageDigest.isEqual(
            bytes.copyOfRange(FILE_BYTES - 32, FILE_BYTES),
            MessageDigest.getInstance("SHA-256").digest(data)
        )) { "Corrupt provisioning record" }
        val reader = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val prefix = ByteArray(4).also { reader.get(it) }
        require(prefix.contentEquals(magic)) { "Unknown provisioning version" }
        val vault = ByteArray(32).also { reader.get(it) }.toHex()
        val generation = ByteArray(32).also { reader.get(it) }.toHex()
        val epoch = reader.long
        val phaseCode = reader.get().toInt() and 0xff
        val phase = VaultProvisioningPolicy.JournalPhase.entries.getOrNull(phaseCode)
            ?: throw IllegalArgumentException("Unknown provisioning phase")
        return VaultProvisioningJournal(vault, generation, epoch, phase)
    }

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
