package org.lepotager.resiliencevault.crypto

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Canonical authenticated context only. No encryption implementation or key storage.
 * Values must come from the expected trusted inventory, not an untrusted blob header.
 */
data class VaultBinding(
    val purpose: Purpose,
    val vaultIdHex: String,
    val generationHex: String,
    val objectIdHex: String,
    val keyEpoch: Long,
    val revision: Long
) {
    enum class Purpose(val code: Int) {
        OBJECT_DATA(1), OBJECT_KEY(2), MANIFEST(3), EPOCH_LOCAL(4), EPOCH_RECOVERY(5)
    }

    fun associatedData(): ByteArray {
        val ids = listOf(vaultIdHex, generationHex, objectIdHex)
        require(ids.all { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } })
        require(keyEpoch > 0 && revision >= 0)
        val out = ByteArrayOutputStream(120)
        DataOutputStream(out).use { data ->
            data.write(byteArrayOf(0x52, 0x56, 0x42, 0x31)) // RVB1
            data.writeShort(1) // format version; unsigned big endian
            data.writeByte(purpose.code)
            data.writeByte(1) // suite: Tink AES256_GCM_HKDF_1MB + AES256_GCM envelopes
            ids.forEach { hex -> hex.chunked(2).forEach { data.writeByte(it.toInt(16)) } }
            data.writeLong(keyEpoch)
            data.writeLong(revision)
        }
        return out.toByteArray()
    }
}
