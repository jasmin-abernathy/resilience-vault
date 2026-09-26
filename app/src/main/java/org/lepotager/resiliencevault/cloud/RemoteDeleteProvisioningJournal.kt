package org.lepotager.resiliencevault.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class RemoteDeleteProvisioningReason {
    CONFIGURE_DELETE_CAPABILITY,
}

enum class RemoteDeleteProvisioningPhase {
    PREPARED,
    SERVER_CONFIRMED,
    CAPSULE_VERIFIED,
    AUTHORITY_PUBLISHED,
    ABANDONED_NEEDS_RECONCILIATION,
}

enum class RemoteDeleteProvisioningServerState {
    UNCONFIRMED,
    PROVISIONED,
    REVOKED,
    CONFLICT,
}

data class RemoteDeleteProvisioningIdentity(
    val serviceId: String,
    val tenantId: String,
    val vaultIdHex: String,
    val vaultGenerationHex: String,
) {
    init {
        require(isCanonicalLabel(serviceId, 64))
        require(isCanonicalLabel(tenantId, 128))
        require(isLowerHex(vaultIdHex, 64))
        require(isLowerHex(vaultGenerationHex, 64))
    }

    override fun toString(): String = "RemoteDeleteProvisioningIdentity([redacted])"

    companion object {
        private val label = Regex("[a-z0-9][a-z0-9._:@-]*")

        internal fun isCanonicalLabel(value: String, maxChars: Int): Boolean =
            value.length in 1..maxChars && label.matches(value)

        internal fun isLowerHex(value: String, length: Int): Boolean =
            value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

data class RemoteDeleteProvisioningJournal(
    val formatVersion: Int = FORMAT_VERSION,
    val reason: RemoteDeleteProvisioningReason =
        RemoteDeleteProvisioningReason.CONFIGURE_DELETE_CAPABILITY,
    val operationIdHex: String,
    val identity: RemoteDeleteProvisioningIdentity,
    val authorityRevisionAtBegin: Long,
    val requestDigestHex: String,
    val capsuleDigestHex: String,
    val phase: RemoteDeleteProvisioningPhase,
    val lastConfirmedServerState: RemoteDeleteProvisioningServerState,
) {
    init {
        require(formatVersion == FORMAT_VERSION)
        require(RemoteDeleteProvisioningIdentity.isLowerHex(operationIdHex, 64))
        require(authorityRevisionAtBegin > 0)
        require(RemoteDeleteProvisioningIdentity.isLowerHex(requestDigestHex, 64))
        require(RemoteDeleteProvisioningIdentity.isLowerHex(capsuleDigestHex, 64))
        validatePhaseServerState(phase, lastConfirmedServerState)
    }

    fun validateTransitionFrom(previous: RemoteDeleteProvisioningJournal) {
        require(formatVersion == previous.formatVersion)
        require(reason == previous.reason)
        require(operationIdHex == previous.operationIdHex)
        require(identity == previous.identity)
        require(authorityRevisionAtBegin == previous.authorityRevisionAtBegin)
        require(requestDigestHex == previous.requestDigestHex)
        require(capsuleDigestHex == previous.capsuleDigestHex)
        require(serverStateCanFollow(
            previous.lastConfirmedServerState,
            lastConfirmedServerState,
        ))
        require(phaseCanFollow(previous.phase, phase))
        validatePhaseServerState(phase, lastConfirmedServerState)
    }

    override fun toString(): String =
        "RemoteDeleteProvisioningJournal(phase=" + phase +
            ", server=" + lastConfirmedServerState + ", [redacted])"

    companion object {
        const val FORMAT_VERSION = 1

        fun prepared(
            operationIdHex: String,
            identity: RemoteDeleteProvisioningIdentity,
            authorityRevisionAtBegin: Long,
            requestDigestHex: String,
            capsuleDigestHex: String,
        ): RemoteDeleteProvisioningJournal =
            RemoteDeleteProvisioningJournal(
                operationIdHex = operationIdHex,
                identity = identity,
                authorityRevisionAtBegin = authorityRevisionAtBegin,
                requestDigestHex = requestDigestHex,
                capsuleDigestHex = capsuleDigestHex,
                phase = RemoteDeleteProvisioningPhase.PREPARED,
                lastConfirmedServerState = RemoteDeleteProvisioningServerState.UNCONFIRMED,
            )

        private fun validatePhaseServerState(
            phase: RemoteDeleteProvisioningPhase,
            server: RemoteDeleteProvisioningServerState,
        ) {
            when (phase) {
                RemoteDeleteProvisioningPhase.PREPARED ->
                    require(server == RemoteDeleteProvisioningServerState.UNCONFIRMED)

                RemoteDeleteProvisioningPhase.SERVER_CONFIRMED,
                RemoteDeleteProvisioningPhase.CAPSULE_VERIFIED,
                RemoteDeleteProvisioningPhase.AUTHORITY_PUBLISHED ->
                    require(server == RemoteDeleteProvisioningServerState.PROVISIONED)

                RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION -> Unit
            }
        }

        private fun phaseCanFollow(
            previous: RemoteDeleteProvisioningPhase,
            next: RemoteDeleteProvisioningPhase,
        ): Boolean =
            when (previous) {
                RemoteDeleteProvisioningPhase.PREPARED ->
                    next == RemoteDeleteProvisioningPhase.SERVER_CONFIRMED ||
                        next == RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION

                RemoteDeleteProvisioningPhase.SERVER_CONFIRMED ->
                    next == RemoteDeleteProvisioningPhase.CAPSULE_VERIFIED ||
                        next == RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION

                RemoteDeleteProvisioningPhase.CAPSULE_VERIFIED ->
                    next == RemoteDeleteProvisioningPhase.AUTHORITY_PUBLISHED ||
                        next == RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION

                RemoteDeleteProvisioningPhase.AUTHORITY_PUBLISHED -> false

                RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION ->
                    next == RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION
            }

        private fun serverStateCanFollow(
            previous: RemoteDeleteProvisioningServerState,
            next: RemoteDeleteProvisioningServerState,
        ): Boolean =
            when (previous) {
                RemoteDeleteProvisioningServerState.UNCONFIRMED ->
                    next == RemoteDeleteProvisioningServerState.UNCONFIRMED ||
                        next == RemoteDeleteProvisioningServerState.PROVISIONED ||
                        next == RemoteDeleteProvisioningServerState.REVOKED ||
                        next == RemoteDeleteProvisioningServerState.CONFLICT

                RemoteDeleteProvisioningServerState.PROVISIONED ->
                    next == RemoteDeleteProvisioningServerState.PROVISIONED ||
                        next == RemoteDeleteProvisioningServerState.REVOKED

                RemoteDeleteProvisioningServerState.REVOKED ->
                    next == RemoteDeleteProvisioningServerState.REVOKED

                RemoteDeleteProvisioningServerState.CONFLICT ->
                    next == RemoteDeleteProvisioningServerState.CONFLICT
            }
    }
}

internal object RemoteDeleteProvisioningJournalCodec {
    private const val MAGIC = 0x5256504A // RVPJ
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val MAX_TEXT_BYTES = 256
    const val MAX_FILE_BYTES = 8 * 1024

    fun encode(record: RemoteDeleteProvisioningJournal): ByteArray {
        val payload = ByteArrayOutputStream().use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(record.formatVersion)
                writeAscii(out, record.reason.name)
                writeAscii(out, record.operationIdHex)
                writeAscii(out, record.identity.serviceId)
                writeAscii(out, record.identity.tenantId)
                writeAscii(out, record.identity.vaultIdHex)
                writeAscii(out, record.identity.vaultGenerationHex)
                out.writeLong(record.authorityRevisionAtBegin)
                writeAscii(out, record.requestDigestHex)
                writeAscii(out, record.capsuleDigestHex)
                writeAscii(out, record.phase.name)
                writeAscii(out, record.lastConfirmedServerState.name)
            }
            raw.toByteArray()
        }
        require(payload.size + DIGEST_BYTES <= MAX_FILE_BYTES)
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(bytes: ByteArray): RemoteDeleteProvisioningJournal {
        require(bytes.size in (DIGEST_BYTES + 32)..MAX_FILE_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val digest = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        require(
            MessageDigest.isEqual(
                digest,
                MessageDigest.getInstance("SHA-256").digest(payload),
            )
        ) { "Corrupt remote provisioning journal" }

        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Unknown provisioning journal magic" }
            require(input.readInt() == VERSION) { "Unknown provisioning journal version" }
            val record = RemoteDeleteProvisioningJournal(
                formatVersion = input.readInt(),
                reason = RemoteDeleteProvisioningReason.valueOf(readAscii(input, 64)),
                operationIdHex = readAscii(input, 64),
                identity = RemoteDeleteProvisioningIdentity(
                    serviceId = readAscii(input, 64),
                    tenantId = readAscii(input, 128),
                    vaultIdHex = readAscii(input, 64),
                    vaultGenerationHex = readAscii(input, 64),
                ),
                authorityRevisionAtBegin = input.readLong(),
                requestDigestHex = readAscii(input, 64),
                capsuleDigestHex = readAscii(input, 64),
                phase = RemoteDeleteProvisioningPhase.valueOf(readAscii(input, 64)),
                lastConfirmedServerState =
                    RemoteDeleteProvisioningServerState.valueOf(readAscii(input, 64)),
            )
            require(input.available() == 0) { "Trailing provisioning journal bytes" }
            record
        }
    }

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
}
