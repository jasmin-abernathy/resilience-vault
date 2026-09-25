package org.lepotager.resiliencevault.panic

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

internal object LegacyPanicStateV1Fixture {
    private const val MAGIC = 0x52565053
    private const val VERSION = 1

    fun encode(
        phase: String,
        arm: ArmedRemotePanic? = null,
        panicIdHex: String? = null,
        purgeComplete: Boolean = false,
        sessionRevocationComplete: Boolean = false,
        remoteDeleteComplete: Boolean = false,
    ): ByteArray {
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { out ->
            out.writeUTF(phase)
            out.writeBoolean(panicIdHex != null)
            panicIdHex?.let(out::writeUTF)
            out.writeBoolean(purgeComplete)
            out.writeBoolean(sessionRevocationComplete)
            out.writeBoolean(remoteDeleteComplete)
            out.writeBoolean(arm != null)
            if (arm != null) {
                out.writeUTF(arm.generationHex)
                out.writeUTF(arm.bootId)
                out.writeLong(arm.startedElapsedRealtimeMs)
                out.writeLong(arm.startedUtcMs)
                out.writeLong(arm.durationMs)
                out.writeInt(arm.contacts.size)
                arm.contacts.forEach { contact ->
                    out.writeUTF(contact.e164)
                    out.writeUTF(contact.verifierHex)
                }
            }
        }
        val payload = payloadBuffer.toByteArray()
        val crc = CRC32().apply { update(payload) }.value
        val file = ByteArrayOutputStream()
        DataOutputStream(file).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(payload.size)
            out.write(payload)
            out.writeLong(crc)
        }
        return file.toByteArray()
    }

    fun version(bytes: ByteArray): Int =
        java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
            input.readInt()
            input.readInt()
        }
}
