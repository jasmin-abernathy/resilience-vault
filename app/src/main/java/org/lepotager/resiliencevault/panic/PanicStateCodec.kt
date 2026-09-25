package org.lepotager.resiliencevault.panic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

internal object PanicStateCodec {
    private const val MAGIC = 0x52565053
    private const val LEGACY_VERSION = 1
    private const val VERSION = 2
    private const val MAX_PAYLOAD_BYTES = 32 * 1024
    const val MAX_FILE_BYTES = MAX_PAYLOAD_BYTES + 20

    fun encode(state: PanicPersistentState): ByteArray {
        state.validate()
        val payload = encodeV2Payload(state)
        return frame(VERSION, payload)
    }

    fun decode(bytes: ByteArray): PanicPersistentState {
        try {
            require(bytes.size <= MAX_FILE_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC)
                val version = input.readInt()
                val payloadSize = input.readInt()
                require(payloadSize in 1..MAX_PAYLOAD_BYTES)

                val payload = ByteArray(payloadSize)
                input.readFully(payload)
                val expectedCrc = input.readLong()
                require(input.available() == 0)
                require(expectedCrc == CRC32().apply { update(payload) }.value)

                val state = when (version) {
                    LEGACY_VERSION -> migrateV1(decodeV1Payload(payload))
                    VERSION -> decodeV2Payload(payload)
                    else -> error("Unknown panic state version")
                }
                state.validate()
                return state
            }
        } catch (error: Exception) {
            throw PanicStateCorruptionException(error)
        }
    }

    private fun encodeV2Payload(state: PanicPersistentState): ByteArray {
        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { out ->
            out.writeUTF(state.phase.name)
            writeArm(out, state.arm)
            out.writeBoolean(state.panicIdHex != null)
            state.panicIdHex?.let(out::writeUTF)
            out.writeBoolean(state.purgeComplete)
            out.writeBoolean(state.sessionRevocationComplete)
            out.writeUTF(state.remoteDeleteConfiguration.name)
            out.writeBoolean(state.remoteDeleteCheckpoint != null)
            state.remoteDeleteCheckpoint?.let { out.writeUTF(it.name) }
            out.writeBoolean(state.remoteDeleteIntent != null)
            state.remoteDeleteIntent?.let { intent ->
                out.writeInt(intent.capsuleFormatVersion)
                out.writeUTF(intent.capsuleIdHex)
                out.writeUTF(intent.tenantId)
                out.writeUTF(intent.vaultIdHex)
                out.writeUTF(intent.vaultGenerationHex)
                out.writeUTF(intent.serviceId)
                out.writeUTF(intent.capsuleSha256Hex)
            }
            out.writeBoolean(state.legacyRemoteUnproven)
            out.writeBoolean(state.legacyRemoteDeleteComplete)
        }
        return payloadBuffer.toByteArray().also { require(it.size <= MAX_PAYLOAD_BYTES) }
    }

    private fun decodeV2Payload(payload: ByteArray): PanicPersistentState =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val phase = PanicPhase.valueOf(input.readUTF())
            val arm = readArm(input)
            val panicId = if (input.readBoolean()) input.readUTF() else null
            val purgeComplete = input.readBoolean()
            val sessionRevocationComplete = input.readBoolean()
            val configuration = RemoteDeleteConfiguration.valueOf(input.readUTF())
            val checkpoint =
                if (input.readBoolean()) RemoteDeleteCheckpoint.valueOf(input.readUTF()) else null
            val intent =
                if (input.readBoolean()) {
                    RemoteDeleteIntent(
                        capsuleFormatVersion = input.readInt(),
                        capsuleIdHex = input.readUTF(),
                        tenantId = input.readUTF(),
                        vaultIdHex = input.readUTF(),
                        vaultGenerationHex = input.readUTF(),
                        serviceId = input.readUTF(),
                        capsuleSha256Hex = input.readUTF(),
                    )
                } else {
                    null
                }
            val legacyRemoteUnproven = input.readBoolean()
            val legacyRemoteDeleteComplete = input.readBoolean()
            require(input.available() == 0)
            PanicPersistentState(
                phase = phase,
                arm = arm,
                panicIdHex = panicId,
                purgeComplete = purgeComplete,
                sessionRevocationComplete = sessionRevocationComplete,
                remoteDeleteConfiguration = configuration,
                remoteDeleteCheckpoint = checkpoint,
                remoteDeleteIntent = intent,
                legacyRemoteUnproven = legacyRemoteUnproven,
                legacyRemoteDeleteComplete = legacyRemoteDeleteComplete,
            )
        }

    private data class LegacyV1State(
        val phase: LegacyV1Phase,
        val arm: ArmedRemotePanic?,
        val panicIdHex: String?,
        val purgeComplete: Boolean,
        val sessionRevocationComplete: Boolean,
        val remoteDeleteComplete: Boolean,
    )

    private enum class LegacyV1Phase {
        IDLE,
        LOCAL_PENDING,
        POST_PENDING,
        COMPLETE,
    }

    private fun decodeV1Payload(payload: ByteArray): LegacyV1State =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val phase = LegacyV1Phase.valueOf(input.readUTF())
            val panicId = if (input.readBoolean()) input.readUTF() else null
            val purgeComplete = input.readBoolean()
            val sessionRevocationComplete = input.readBoolean()
            val remoteDeleteComplete = input.readBoolean()
            val arm = readArm(input)
            require(input.available() == 0)

            val legacy = LegacyV1State(
                phase,
                arm,
                panicId,
                purgeComplete,
                sessionRevocationComplete,
                remoteDeleteComplete,
            )
            validateV1(legacy)
            legacy
        }

    private fun migrateV1(legacy: LegacyV1State): PanicPersistentState =
        when (legacy.phase) {
            LegacyV1Phase.IDLE ->
                PanicPersistentState(
                    phase = PanicPhase.IDLE,
                    arm = legacy.arm,
                    remoteDeleteConfiguration = RemoteDeleteConfiguration.UNKNOWN,
                )

            LegacyV1Phase.LOCAL_PENDING ->
                PanicPersistentState(
                    phase = PanicPhase.LOCAL_PENDING,
                    panicIdHex = legacy.panicIdHex,
                    purgeComplete = legacy.purgeComplete,
                    sessionRevocationComplete = legacy.sessionRevocationComplete,
                    remoteDeleteConfiguration = RemoteDeleteConfiguration.UNKNOWN,
                    remoteDeleteCheckpoint = RemoteDeleteCheckpoint.LEGACY_UNPROVEN,
                    legacyRemoteUnproven = true,
                    legacyRemoteDeleteComplete = legacy.remoteDeleteComplete,
                )

            LegacyV1Phase.POST_PENDING ->
                PanicPersistentState(
                    phase = PanicPhase.POST_PENDING,
                    panicIdHex = legacy.panicIdHex,
                    purgeComplete = legacy.purgeComplete,
                    sessionRevocationComplete = legacy.sessionRevocationComplete,
                    remoteDeleteConfiguration = RemoteDeleteConfiguration.UNKNOWN,
                    remoteDeleteCheckpoint = RemoteDeleteCheckpoint.LEGACY_UNPROVEN,
                    legacyRemoteUnproven = true,
                    legacyRemoteDeleteComplete = legacy.remoteDeleteComplete,
                )

            LegacyV1Phase.COMPLETE ->
                PanicPersistentState(
                    phase = PanicPhase.LEGACY_COMPLETE_UNVERIFIED,
                    panicIdHex = legacy.panicIdHex,
                    purgeComplete = legacy.purgeComplete,
                    sessionRevocationComplete = legacy.sessionRevocationComplete,
                    remoteDeleteConfiguration = RemoteDeleteConfiguration.UNKNOWN,
                    remoteDeleteCheckpoint = RemoteDeleteCheckpoint.LEGACY_UNPROVEN,
                    legacyRemoteUnproven = true,
                    legacyRemoteDeleteComplete = legacy.remoteDeleteComplete,
                )
        }

    private fun validateV1(state: LegacyV1State) {
        when (state.phase) {
            LegacyV1Phase.IDLE -> {
                require(state.panicIdHex == null)
                require(!state.purgeComplete)
                require(!state.sessionRevocationComplete)
                require(!state.remoteDeleteComplete)
            }
            LegacyV1Phase.LOCAL_PENDING -> {
                require(state.arm == null)
                require(RemotePanicCommand.isLowerHex256(state.panicIdHex))
                require(!state.purgeComplete)
                require(!state.sessionRevocationComplete)
                require(!state.remoteDeleteComplete)
            }
            LegacyV1Phase.POST_PENDING -> {
                require(state.arm == null)
                require(RemotePanicCommand.isLowerHex256(state.panicIdHex))
            }
            LegacyV1Phase.COMPLETE -> {
                require(state.arm == null)
                require(RemotePanicCommand.isLowerHex256(state.panicIdHex))
                require(state.purgeComplete)
                require(state.sessionRevocationComplete)
                require(state.remoteDeleteComplete)
            }
        }

        state.arm?.let { arm ->
            PanicPersistentState(
                phase = PanicPhase.IDLE,
                arm = arm,
                remoteDeleteConfiguration = RemoteDeleteConfiguration.UNKNOWN,
            ).validate()
        }
    }

    private fun writeArm(out: DataOutputStream, arm: ArmedRemotePanic?) {
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

    private fun readArm(input: DataInputStream): ArmedRemotePanic? {
        if (!input.readBoolean()) return null
        val generationHex = input.readUTF()
        val bootId = input.readUTF()
        val startedElapsed = input.readLong()
        val startedUtc = input.readLong()
        val duration = input.readLong()
        val count = input.readInt()
        require(count in 1..RemotePanicPolicy.MAX_CONTACTS)
        val contacts = buildList {
            repeat(count) {
                add(
                    TrustedContactVerifier(
                        e164 = input.readUTF(),
                        verifierHex = input.readUTF(),
                    )
                )
            }
        }
        return ArmedRemotePanic(
            generationHex = generationHex,
            bootId = bootId,
            startedElapsedRealtimeMs = startedElapsed,
            startedUtcMs = startedUtc,
            durationMs = duration,
            contacts = contacts,
        )
    }

    private fun frame(version: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES)
        val crc = CRC32().apply { update(payload) }.value
        val fileBuffer = ByteArrayOutputStream()
        DataOutputStream(fileBuffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(version)
            out.writeInt(payload.size)
            out.write(payload)
            out.writeLong(crc)
        }
        return fileBuffer.toByteArray()
    }
}

internal class PanicStateCorruptionException(
    cause: Throwable
) : IllegalStateException("Remote panic state is unreadable", cause)
