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
    COMPLETE
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
    val remoteDeleteComplete: Boolean = false
) {
    companion object {
        fun initial(): PanicPersistentState = PanicPersistentState()
    }

    /** A valid standalone record must also be a legal successor of the stored record. */
    fun validateTransitionFrom(previous: PanicPersistentState) {
        validate()
        previous.validate()
        when (previous.phase) {
            PanicPhase.IDLE -> require(phase == PanicPhase.IDLE || phase == PanicPhase.LOCAL_PENDING)
            PanicPhase.LOCAL_PENDING -> require(phase == PanicPhase.LOCAL_PENDING || phase == PanicPhase.POST_PENDING)
            PanicPhase.POST_PENDING -> require(phase == PanicPhase.POST_PENDING || phase == PanicPhase.COMPLETE)
            PanicPhase.COMPLETE -> require(this == previous)
        }
        if (previous.phase != PanicPhase.IDLE) {
            require(panicIdHex == previous.panicIdHex)
            require(!previous.purgeComplete || purgeComplete)
            require(!previous.sessionRevocationComplete || sessionRevocationComplete)
            require(!previous.remoteDeleteComplete || remoteDeleteComplete)
        }
    }

    override fun toString(): String = "PanicPersistentState(phase=$phase, [redacted])"

    fun validate() {
        when (phase) {
            PanicPhase.IDLE -> {
                require(panicIdHex == null)
                require(!purgeComplete && !sessionRevocationComplete && !remoteDeleteComplete)
            }
            PanicPhase.LOCAL_PENDING -> {
                require(arm == null)
                require(RemotePanicCommand.isLowerHex256(panicIdHex))
                require(!purgeComplete && !sessionRevocationComplete && !remoteDeleteComplete)
            }
            PanicPhase.POST_PENDING -> {
                require(arm == null)
                require(RemotePanicCommand.isLowerHex256(panicIdHex))
            }
            PanicPhase.COMPLETE -> {
                require(arm == null)
                require(RemotePanicCommand.isLowerHex256(panicIdHex))
                require(purgeComplete && sessionRevocationComplete && remoteDeleteComplete)
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
}
