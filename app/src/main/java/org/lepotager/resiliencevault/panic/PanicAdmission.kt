package org.lepotager.resiliencevault.panic

data class RemoteArmRequest(
    val canonicalContactsE164: List<String>,
    val durationMs: Long
)

data class TrustedContactCommand(
    val e164: String,
    val command: String
) {
    override fun toString(): String = "TrustedContactCommand([redacted])"
}

sealed interface RemoteArmResult {
    data class Armed(
        val commands: List<TrustedContactCommand>,
        val expiresUtcMs: Long
    ) : RemoteArmResult

    data class Rejected(val reason: ArmRejectionReason) : RemoteArmResult
    data class StorageUnavailable(val failure: PanicStoreFailure) : RemoteArmResult
}

enum class ArmRejectionReason {
    PANIC_ACTIVE,
    REMOTE_CHANNEL_UNAVAILABLE,
    INVALID_CLOCK,
    INVALID_DURATION,
    INVALID_CONTACTS,
    ENTROPY_FAILURE,
    REMOTE_DELETE_PROOF_REQUIRED
}

data class ValidatedSmsEnvelope(
    val senderE164: String,
    val body: String,
    val trustedSystemDelivery: Boolean,
    val completeMessage: Boolean
) {
    override fun toString(): String = "ValidatedSmsEnvelope([redacted])"
}

sealed interface AdmissionResult {
    data class Accepted(val panicIdHex: String) : AdmissionResult
    data class Rejected(val reason: AdmissionRejectionReason) : AdmissionResult
    data class StorageUnavailable(val failure: PanicStoreFailure) : AdmissionResult
}

enum class AdmissionRejectionReason {
    PANIC_ACTIVE,
    NOT_ARMED,
    EXPIRED_OR_INVALIDATED,
    UNTRUSTED_ENVELOPE,
    SENDER_NOT_ALLOWED,
    MALFORMED_COMMAND,
    INVALID_SECRET
}

class PanicAdmissionService(
    private val store: PanicStateStore,
    private val environment: PanicAdmissionEnvironment = PanicAdmissionEnvironment {
        PanicAdmissionObservation(PanicClockSnapshot(null, -1, -1), false)
    },
    private val tokenGenerator: RemotePanicTokenGenerator = RemotePanicTokenGenerator()
) {
    suspend fun armRemote(request: RemoteArmRequest): RemoteArmResult {
        return when (val result = store.transaction { state ->
            if (state.phase != PanicPhase.IDLE) {
                return@transaction PanicStateMutation.Keep(
                    RemoteArmResult.Rejected(ArmRejectionReason.PANIC_ACTIVE)
                )
            }
            if (state.remoteDeleteConfiguration != RemoteDeleteConfiguration.NOT_CONFIGURED) {
                return@transaction PanicStateMutation.Keep(
                    RemoteArmResult.Rejected(ArmRejectionReason.REMOTE_DELETE_PROOF_REQUIRED)
                )
            }
            // Observe after obtaining the store lock, never trust a caller's timestamp.
            val observation = observeOrUnavailable()
            val failure = validateArmRequest(request, observation)
            if (failure != null) {
                return@transaction PanicStateMutation.Replace(
                    state.copy(arm = null), RemoteArmResult.Rejected(failure)
                )
            }
            val generation: String
            val secrets: Map<String, String>
            try {
                generation = tokenGenerator.newHex256()
                secrets = request.canonicalContactsE164.associateWith { tokenGenerator.newHex256() }
                check(secrets.values.toSet().size == secrets.size)
                check(generation !in secrets.values)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@transaction PanicStateMutation.Replace(
                    state.copy(arm = null), RemoteArmResult.Rejected(ArmRejectionReason.ENTROPY_FAILURE)
                )
            }
            // RNG/provider work may have delayed us; validate a fresh observation again.
            val finalObservation = observeOrUnavailable()
            val finalFailure = validateArmRequest(request, finalObservation)
            if (finalFailure != null || finalObservation.clock.bootId != observation.clock.bootId) {
                return@transaction PanicStateMutation.Replace(
                    state.copy(arm = null),
                    RemoteArmResult.Rejected(finalFailure ?: ArmRejectionReason.INVALID_CLOCK)
                )
            }
            val clock = finalObservation.clock
            val contacts = secrets.map { (number, secret) ->
                TrustedContactVerifier(number, RemotePanicCommand.verifierHex(generation, number, secret))
            }
            val armed = ArmedRemotePanic(
                generation, clock.bootId!!, clock.elapsedRealtimeMs, clock.utcMs,
                request.durationMs, contacts
            )
            PanicStateMutation.Replace(
                state.copy(arm = armed),
                RemoteArmResult.Armed(
                    secrets.map { (number, secret) ->
                        TrustedContactCommand(number, RemotePanicCommand.build(generation, secret))
                    },
                    Math.addExact(clock.utcMs, request.durationMs)
                )
            )
        }) {
            is PanicTransactionResult.Success -> result.value
            is PanicTransactionResult.Unavailable -> RemoteArmResult.StorageUnavailable(result.failure)
        }
    }

    /** Persist invalidation when UI/startup observes it; a later clock rollback cannot revive it. */
    suspend fun refreshRemoteState(): PanicStoreReadResult =
        when (val result = store.transaction { state ->
            val arm = state.arm
            if (arm == null || state.phase != PanicPhase.IDLE) {
                PanicStateMutation.Keep(Unit)
            } else {
                val observation = observeOrUnavailable()
                if (!observation.smsChannelReady || !RemotePanicWindow.isValid(arm, observation.clock)) {
                    PanicStateMutation.Replace(state.copy(arm = null), Unit)
                } else {
                    PanicStateMutation.Keep(Unit)
                }
            }
        }) {
            is PanicTransactionResult.Success -> PanicStoreReadResult.Ready(result.state)
            is PanicTransactionResult.Unavailable -> PanicStoreReadResult.Unavailable(result.failure)
        }

    suspend fun disarmRemote(): PanicTransactionResult<Boolean> =
        store.transaction { state ->
            if (state.phase != PanicPhase.IDLE || state.arm == null) {
                PanicStateMutation.Keep(false)
            } else {
                PanicStateMutation.Replace(state.copy(arm = null), true)
            }
        }

    suspend fun removeTrustedContact(e164: String): PanicTransactionResult<Boolean> =
        store.transaction { state ->
            val arm = state.arm
            if (state.phase != PanicPhase.IDLE || arm == null) {
                PanicStateMutation.Keep(false)
            } else {
                val remaining = arm.contacts.filterNot { it.e164 == e164 }
                if (remaining.size == arm.contacts.size) {
                    PanicStateMutation.Keep(false)
                } else {
                    PanicStateMutation.Replace(
                        state.copy(arm = if (remaining.isEmpty()) null else arm.copy(contacts = remaining)),
                        true
                    )
                }
            }
        }

    suspend fun acceptSms(envelope: ValidatedSmsEnvelope): AdmissionResult {
        if (!envelope.trustedSystemDelivery || !envelope.completeMessage) {
            return AdmissionResult.Rejected(AdmissionRejectionReason.UNTRUSTED_ENVELOPE)
        }

        val panicId = tokenGenerator.newHex256()
        return when (val result = store.transaction { state ->
            if (state.phase != PanicPhase.IDLE) {
                return@transaction PanicStateMutation.Keep(
                    AdmissionResult.Rejected(AdmissionRejectionReason.PANIC_ACTIVE)
                )
            }

            val arm = state.arm ?: return@transaction PanicStateMutation.Keep(
                AdmissionResult.Rejected(AdmissionRejectionReason.NOT_ARMED)
            )

            val observation = observeOrUnavailable()
            if (!observation.smsChannelReady || !RemotePanicWindow.isValid(arm, observation.clock)) {
                return@transaction PanicStateMutation.Replace(
                    state.copy(arm = null),
                    AdmissionResult.Rejected(AdmissionRejectionReason.EXPIRED_OR_INVALIDATED)
                )
            }

            val contact = arm.contacts.firstOrNull { it.e164 == envelope.senderE164 }
                ?: return@transaction PanicStateMutation.Keep(
                    AdmissionResult.Rejected(AdmissionRejectionReason.SENDER_NOT_ALLOWED)
                )

            val parsed = RemotePanicCommand.parseExact(envelope.body)
                ?: return@transaction PanicStateMutation.Keep(
                    AdmissionResult.Rejected(AdmissionRejectionReason.MALFORMED_COMMAND)
                )

            if (parsed.generationHex != arm.generationHex) {
                return@transaction PanicStateMutation.Keep(
                    AdmissionResult.Rejected(AdmissionRejectionReason.INVALID_SECRET)
                )
            }

            if (!RemotePanicCommand.verifierMatches(
                    expectedVerifierHex = contact.verifierHex,
                    generationHex = parsed.generationHex,
                    e164 = contact.e164,
                    secretHex = parsed.secretHex
                )
            ) {
                return@transaction PanicStateMutation.Keep(
                    AdmissionResult.Rejected(AdmissionRejectionReason.INVALID_SECRET)
                )
            }

            val finalObservation = observeOrUnavailable()
            if (!finalObservation.smsChannelReady || !RemotePanicWindow.isValid(arm, finalObservation.clock)) {
                return@transaction PanicStateMutation.Replace(
                    state.copy(arm = null),
                    AdmissionResult.Rejected(AdmissionRejectionReason.EXPIRED_OR_INVALIDATED)
                )
            }

            PanicStateMutation.Replace(
                state = state.copy(
                    phase = PanicPhase.LOCAL_PENDING,
                    arm = null,
                    panicIdHex = panicId,
                    purgeComplete = false,
                    sessionRevocationComplete = false,
                    remoteDeleteCheckpoint = RemoteDeleteCheckpoint.NOT_CONFIGURED,
                    remoteDeleteIntent = null,
                    legacyRemoteUnproven = false,
                    legacyRemoteDeleteComplete = false
                ),
                value = AdmissionResult.Accepted(panicId)
            )
        }) {
            is PanicTransactionResult.Success -> result.value
            is PanicTransactionResult.Unavailable -> AdmissionResult.StorageUnavailable(result.failure)
        }
    }

    suspend fun acceptLocal(): AdmissionResult {
        val panicId = tokenGenerator.newHex256()
        return when (val result = store.transaction { state ->
            if (state.phase != PanicPhase.IDLE) {
                PanicStateMutation.Keep(
                    AdmissionResult.Rejected(AdmissionRejectionReason.PANIC_ACTIVE)
                )
            } else {
                PanicStateMutation.Replace(
                    state = localPanicState(state, panicId),
                    value = AdmissionResult.Accepted(panicId)
                )
            }
        }) {
            is PanicTransactionResult.Success -> result.value
            is PanicTransactionResult.Unavailable -> AdmissionResult.StorageUnavailable(result.failure)
        }
    }

    private fun localPanicState(
        state: PanicPersistentState,
        panicId: String,
    ): PanicPersistentState =
        if (state.remoteDeleteConfiguration == RemoteDeleteConfiguration.NOT_CONFIGURED) {
            state.copy(
                phase = PanicPhase.LOCAL_PENDING,
                arm = null,
                panicIdHex = panicId,
                purgeComplete = false,
                sessionRevocationComplete = false,
                remoteDeleteCheckpoint = RemoteDeleteCheckpoint.NOT_CONFIGURED,
                remoteDeleteIntent = null,
                legacyRemoteUnproven = false,
                legacyRemoteDeleteComplete = false,
            )
        } else {
            state.copy(
                phase = PanicPhase.LOCAL_PENDING,
                arm = null,
                panicIdHex = panicId,
                purgeComplete = false,
                sessionRevocationComplete = false,
                remoteDeleteCheckpoint = RemoteDeleteCheckpoint.LEGACY_UNPROVEN,
                remoteDeleteIntent = null,
                legacyRemoteUnproven = true,
                legacyRemoteDeleteComplete = false,
            )
        }

    private fun observeOrUnavailable(): PanicAdmissionObservation =
        try {
            environment.observe()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PanicAdmissionObservation(PanicClockSnapshot(null, -1, -1), false)
        }

    private fun validateArmRequest(
        request: RemoteArmRequest, observation: PanicAdmissionObservation
    ): ArmRejectionReason? {
        if (!observation.smsChannelReady) return ArmRejectionReason.REMOTE_CHANNEL_UNAVAILABLE
        if (observation.clock.bootId.isNullOrBlank() ||
            observation.clock.elapsedRealtimeMs < 0 ||
            observation.clock.utcMs < 0
        ) {
            return ArmRejectionReason.INVALID_CLOCK
        }
        if (request.durationMs !in RemotePanicPolicy.allowedDurationsMs) {
            return ArmRejectionReason.INVALID_DURATION
        }
        try {
            Math.addExact(observation.clock.utcMs, request.durationMs)
            Math.addExact(observation.clock.elapsedRealtimeMs, request.durationMs)
        } catch (_: ArithmeticException) {
            return ArmRejectionReason.INVALID_CLOCK
        }
        val contacts = request.canonicalContactsE164
        if (contacts.size !in 1..RemotePanicPolicy.MAX_CONTACTS ||
            contacts.distinct().size != contacts.size ||
            contacts.any { !RemotePanicCommand.isCanonicalE164(it) }
        ) {
            return ArmRejectionReason.INVALID_CONTACTS
        }
        return null
    }

}
