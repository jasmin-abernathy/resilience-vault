package org.lepotager.resiliencevault.panic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

internal object PanicStateCodec {
    private const val MAGIC = 0x52565053
    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 32 * 1024

    fun encode(state: PanicPersistentState): ByteArray {
        state.validate()

        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { out ->
            out.writeUTF(state.phase.name)
            out.writeBoolean(state.panicIdHex != null)
            state.panicIdHex?.let(out::writeUTF)
            out.writeBoolean(state.purgeComplete)
            out.writeBoolean(state.sessionRevocationComplete)
            out.writeBoolean(state.remoteDeleteComplete)

            val arm = state.arm
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
        require(payload.size <= MAX_PAYLOAD_BYTES)
        val crc = CRC32().apply { update(payload) }.value

        val fileBuffer = ByteArrayOutputStream()
        DataOutputStream(fileBuffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(payload.size)
            out.write(payload)
            out.writeLong(crc)
        }
        return fileBuffer.toByteArray()
    }

    fun decode(bytes: ByteArray): PanicPersistentState {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == VERSION)
                val payloadSize = input.readInt()
                require(payloadSize in 1..MAX_PAYLOAD_BYTES)

                val payload = ByteArray(payloadSize)
                input.readFully(payload)
                val expectedCrc = input.readLong()
                require(input.available() == 0)

                val actualCrc = CRC32().apply { update(payload) }.value
                require(expectedCrc == actualCrc)

                val state = DataInputStream(ByteArrayInputStream(payload)).use { stateInput ->
                    val phase = PanicPhase.valueOf(stateInput.readUTF())
                    val panicId = if (stateInput.readBoolean()) stateInput.readUTF() else null
                    val purgeComplete = stateInput.readBoolean()
                    val sessionRevocationComplete = stateInput.readBoolean()
                    val remoteDeleteComplete = stateInput.readBoolean()
                    val arm = if (stateInput.readBoolean()) {
                        val generationHex = stateInput.readUTF()
                        val bootId = stateInput.readUTF()
                        val startedElapsed = stateInput.readLong()
                        val startedUtc = stateInput.readLong()
                        val duration = stateInput.readLong()
                        val count = stateInput.readInt()
                        require(count in 1..RemotePanicPolicy.MAX_CONTACTS)
                        val contacts = buildList {
                            repeat(count) {
                                add(
                                    TrustedContactVerifier(
                                        e164 = stateInput.readUTF(),
                                        verifierHex = stateInput.readUTF()
                                    )
                                )
                            }
                        }
                        ArmedRemotePanic(
                            generationHex = generationHex,
                            bootId = bootId,
                            startedElapsedRealtimeMs = startedElapsed,
                            startedUtcMs = startedUtc,
                            durationMs = duration,
                            contacts = contacts
                        )
                    } else {
                        null
                    }
                    require(stateInput.available() == 0)
                    PanicPersistentState(
                        phase = phase,
                        arm = arm,
                        panicIdHex = panicId,
                        purgeComplete = purgeComplete,
                        sessionRevocationComplete = sessionRevocationComplete,
                        remoteDeleteComplete = remoteDeleteComplete
                    )
                }
                state.validate()
                return state
            }
        } catch (error: Throwable) {
            throw PanicStateCorruptionException(error)
        }
    }
}

internal class PanicStateCorruptionException(
    cause: Throwable
) : IllegalStateException("Remote panic state is unreadable", cause)
