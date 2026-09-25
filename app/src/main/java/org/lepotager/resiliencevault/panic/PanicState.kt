package org.lepotager.resiliencevault.panic

object RemotePanicPolicy {
    const val MAX_CONTACTS = 5
    const val DRIFT_TOLERANCE_MS = 2_000L
    const val TOKEN_HEX_LENGTH = 64
    val allowedDurationsMs: Set<Long> =
        setOf(1L, 6L, 12L, 24L, 48L, 72L).mapTo(linkedSetOf()) { it * 60L * 60L * 1_000L }
}

enum class PanicPhase {
    IDLE,
    LOCAL_PENDING,
    POST_PENDING,
    COMPLETE,
    LEGACY_COMPLETE_UNVERIFIED,
}

enum class RemoteDeleteConfiguration {
    UNKNOWN,
    NOT_CONFIGURED,
    CONFIGURED,
}

enum class RemoteDeleteCheckpoint {
    NOT_CONFIGURED,
    PENDING,
    TOMBSTONED_PENDING,
    BLOCKED_AUTH,
    COMPLETE,
    LEGACY_UNPROVEN,
}

data class RemoteDeleteIntent(
    val capsuleFormatVersion: Int,
    val capsuleIdHex: String,
    val tenantId: String,
    val vaultIdHex: String,
    val vaultGenerationHex: String,
    val serviceId: String,
    val capsuleSha256Hex: String,
) {
    init {
        require(capsuleFormatVersion == 1)
        require(isLowerHex(capsuleIdHex, 32) || isLowerHex(capsuleIdHex, 64))
        require(isCanonicalLabel(tenantId, 128))
        require(RemotePanicCommand.isLowerHex256(vaultIdHex))
        require(RemotePanicCommand.isLowerHex256(vaultGenerationHex))
        require(isCanonicalLabel(serviceId, 64))
        require(RemotePanicCommand.isLowerHex256(capsuleSha256Hex))
    }

    override fun toString(): String = "RemoteDeleteIntent([redacted])"

    companion object {
        private val label = Regex("[a-z0-9][a-z0-9._:@-]*")

        private fun isCanonicalLabel(value: String, maxChars: Int): Boolean =
            value.length in 1..maxChars && label.matches(value)

        private fun isLowerHex(value: String, length: Int): Boolean =
            value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

data class PanicClockSnapshot(
    val bootId: String?,
    val elapsedRealtimeMs: Long,
    val utcMs: Long
)

data class TrustedContactVerifier(
    val e164: String,
    val verifierHex: String
) {
    override fun toString(): String = "TrustedContactVerifier([redacted])"
}

data class ArmedRemotePanic(
    val generationHex: String,
    val bootId: String,
    val startedElapsedRealtimeMs: Long,
    val startedUtcMs: Long,
    val durationMs: Long,
    val contacts: List<TrustedContactVerifier>
) {
    override fun toString(): String = "ArmedRemotePanic([redacted])"
}

data class PanicPersistentState(
    val phase: PanicPhase = PanicPhase.IDLE,
    val arm: ArmedRemotePanic? = null,
    val panicIdHex: String? = null,
    val purgeComplete: Boolean = false,
    val sessionRevocationComplete: Boolean = false,
    val remoteDeleteConfiguration: RemoteDeleteConfiguration = RemoteDeleteConfiguration.UNKNOWN,
    val remoteDeleteCheckpoint: RemoteDeleteCheckpoint? = null,
    val remoteDeleteIntent: RemoteDeleteIntent? = null,
    val legacyRemoteUnproven: Boolean = false,
    val legacyRemoteDeleteComplete: Boolean = false,
) {
    companion object {
        fun initial(): PanicPersistentState = PanicPersistentState()

        fun localPendingWithoutRemoteProof(
            panicIdHex: String,
            configuration: RemoteDeleteConfiguration = RemoteDeleteConfiguration.UNKNOWN,
        ): PanicPersistentState =
            PanicPersistentState(
                phase = PanicPhase.LOCAL_PENDING,
                panicIdHex = panicIdHex,
                remoteDeleteConfiguration = configuration,
                remoteDeleteCheckpoint = RemoteDeleteCheckpoint.LEGACY_UNPROVEN,
                legacyRemoteUnproven = true,
            )
    }

    /** A valid standalone record must also be a legal successor of the stored record. */
    fun validateTransitionFrom(previous: PanicPersistentState) {
        validate()
        previous.validate()

        when (previous.phase) {
            PanicPhase.IDLE ->
                require(phase == PanicPhase.IDLE || phase == PanicPhase.LOCAL_PENDING)
            PanicPhase.LOCAL_PENDING ->
                require(phase == PanicPhase.LOCAL_PENDING || phase == PanicPhase.POST_PENDING)
            PanicPhase.POST_PENDING ->
                require(phase == PanicPhase.POST_PENDING || phase == PanicPhase.COMPLETE)
            PanicPhase.COMPLETE,
            PanicPhase.LEGACY_COMPLETE_UNVERIFIED ->
                require(this == previous)
        }

        if (previous.phase != PanicPhase.IDLE) {
            require(panicIdHex == previous.panicIdHex)
            require(!previous.purgeComplete || purgeComplete)
            require(!previous.sessionRevocationComplete || sessionRevocationComplete)
            require(remoteDeleteConfiguration == previous.remoteDeleteConfiguration)
            require(remoteDeleteIntent == previous.remoteDeleteIntent)
            require(legacyRemoteUnproven == previous.legacyRemoteUnproven)
            require(legacyRemoteDeleteComplete == previous.legacyRemoteDeleteComplete)
            require(remoteCheckpointCanFollow(previous.remoteDeleteCheckpoint, remoteDeleteCheckpoint))
        }
    }

    override fun toString(): String =
        "PanicPersistentState(phase=" + phase + ", remote=" + remoteDeleteCheckpoint + ", [redacted])"

    fun validate() {
        when (phase) {
            PanicPhase.IDLE -> {
                require(panicIdHex == null)
                require(!purgeComplete && !sessionRevocationComplete)
                require(remoteDeleteCheckpoint == null)
                require(remoteDeleteIntent == null)
                require(!legacyRemoteUnproven)
                require(!legacyRemoteDeleteComplete)
            }

            PanicPhase.LOCAL_PENDING -> {
                require(arm == null)
                require(RemotePanicCommand.isLowerHex256(panicIdHex))
                require(!purgeComplete && !sessionRevocationComplete)
                validateEngagedRemoteState()
            }

            PanicPhase.POST_PENDING -> {
                require(arm == null)
                require(RemotePanicCommand.isLowerHex256(panicIdHex))
                validateEngagedRemoteState()
            }

            PanicPhase.COMPLETE -> {
                require(arm == null)
                require(RemotePanicCommand.isLowerHex256(panicIdHex))
                require(purgeComplete && sessionRevocationComplete)
                validateEngagedRemoteState()
                require(
                    remoteDeleteCheckpoint == RemoteDeleteCheckpoint.NOT_CONFIGURED ||
                        remoteDeleteCheckpoint == RemoteDeleteCheckpoint.COMPLETE
                )
            }

            PanicPhase.LEGACY_COMPLETE_UNVERIFIED -> {
                require(arm == null)
                require(RemotePanicCommand.isLowerHex256(panicIdHex))
                require(purgeComplete && sessionRevocationComplete)
                require(remoteDeleteConfiguration == RemoteDeleteConfiguration.UNKNOWN)
                require(remoteDeleteCheckpoint == RemoteDeleteCheckpoint.LEGACY_UNPROVEN)
                require(remoteDeleteIntent == null)
                require(legacyRemoteUnproven)
                require(legacyRemoteDeleteComplete)
            }
        }

        arm?.let { armed ->
            require(RemotePanicCommand.isLowerHex256(armed.generationHex))
            require(armed.bootId.isNotBlank())
            require(armed.startedElapsedRealtimeMs >= 0)
            require(armed.startedUtcMs >= 0)
            require(armed.durationMs in RemotePanicPolicy.allowedDurationsMs)
            require(armed.startedUtcMs <= Long.MAX_VALUE - armed.durationMs)
            require(armed.startedElapsedRealtimeMs <= Long.MAX_VALUE - armed.durationMs)
            require(armed.contacts.size in 1..RemotePanicPolicy.MAX_CONTACTS)
            require(armed.contacts.map { it.e164 }.distinct().size == armed.contacts.size)
            require(armed.contacts.all { RemotePanicCommand.isCanonicalE164(it.e164) })
            require(armed.contacts.all { RemotePanicCommand.isLowerHex256(it.verifierHex) })
        }
    }

    fun remoteDeleteAllowsV2Completion(): Boolean =
        remoteDeleteCheckpoint == RemoteDeleteCheckpoint.NOT_CONFIGURED ||
            remoteDeleteCheckpoint == RemoteDeleteCheckpoint.COMPLETE

    private fun validateEngagedRemoteState() {
        when (remoteDeleteCheckpoint) {
            RemoteDeleteCheckpoint.NOT_CONFIGURED -> {
                require(remoteDeleteConfiguration == RemoteDeleteConfiguration.NOT_CONFIGURED)
                require(remoteDeleteIntent == null)
                require(!legacyRemoteUnproven)
                require(!legacyRemoteDeleteComplete)
            }

            RemoteDeleteCheckpoint.PENDING,
            RemoteDeleteCheckpoint.TOMBSTONED_PENDING,
            RemoteDeleteCheckpoint.BLOCKED_AUTH,
            RemoteDeleteCheckpoint.COMPLETE -> {
                require(remoteDeleteConfiguration == RemoteDeleteConfiguration.CONFIGURED)
                require(remoteDeleteIntent != null)
                require(!legacyRemoteUnproven)
                require(!legacyRemoteDeleteComplete)
            }

            RemoteDeleteCheckpoint.LEGACY_UNPROVEN -> {
                require(remoteDeleteConfiguration != RemoteDeleteConfiguration.NOT_CONFIGURED)
                require(remoteDeleteIntent == null)
                require(legacyRemoteUnproven)
            }

            null -> error("Remote delete checkpoint required after panic admission")
        }
    }

    private fun remoteCheckpointCanFollow(
        previous: RemoteDeleteCheckpoint?,
        next: RemoteDeleteCheckpoint?,
    ): Boolean =
        when (previous) {
            null -> next == null
            RemoteDeleteCheckpoint.NOT_CONFIGURED ->
                next == RemoteDeleteCheckpoint.NOT_CONFIGURED
            RemoteDeleteCheckpoint.PENDING ->
                next == RemoteDeleteCheckpoint.PENDING ||
                    next == RemoteDeleteCheckpoint.TOMBSTONED_PENDING ||
                    next == RemoteDeleteCheckpoint.BLOCKED_AUTH ||
                    next == RemoteDeleteCheckpoint.COMPLETE
            RemoteDeleteCheckpoint.TOMBSTONED_PENDING ->
                next == RemoteDeleteCheckpoint.TOMBSTONED_PENDING ||
                    next == RemoteDeleteCheckpoint.BLOCKED_AUTH ||
                    next == RemoteDeleteCheckpoint.COMPLETE
            RemoteDeleteCheckpoint.BLOCKED_AUTH ->
                next == RemoteDeleteCheckpoint.BLOCKED_AUTH
            RemoteDeleteCheckpoint.COMPLETE ->
                next == RemoteDeleteCheckpoint.COMPLETE
            RemoteDeleteCheckpoint.LEGACY_UNPROVEN ->
                next == RemoteDeleteCheckpoint.LEGACY_UNPROVEN
        }
}
