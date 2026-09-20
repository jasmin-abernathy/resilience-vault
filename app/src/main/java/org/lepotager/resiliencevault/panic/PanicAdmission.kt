package org.lepotager.resiliencevault.panic

data class RemoteArmRequest(
    val canonicalContactsE164: List<String>,
    val durationMs: Long,
    val clock: PanicClockSnapshot,
    val remoteChannelReady: Boolean
)

data class TrustedContactCommand(
    val e164: String,
    val command: String
)

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
    INVALID_CONTACTS
}

data class ValidatedSmsEnvelope(
    val senderE164: String,
    val body: String,
    val clock: PanicClockSnapshot,
    val smsPermissionObserved: Boolean,
    val trustedSystemDelivery: Boolean,
    val completeMessage: Boolean
)

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
    private val tokenGenerator: RemotePanicTokenGenerator = RemotePanicTokenGenerator()
) {
    suspend fun armRemote(request: RemoteArmRequest): RemoteArmResult {
        val staticFailure = validateArmRequest(request)
        if (staticFailure != null) {
            return clearExistingArmAndReject(staticFailure)
        }

        val generation = tokenGenerator.newHex256()
        val secrets = request.canonicalContactsE164.associateWith { tokenGenerator.newHex256() }
        val contacts = request.canonicalContactsE164.map { number ->
            TrustedContactVerifier(
                e164 = number,
                verifierHex = RemotePanicCommand.verifierHex(
                    generationHex = generation,
                    e164 = number,
                    secretHex = secrets.getValue(number)
                )
            )
        }
        val armed = ArmedRemotePanic(
            generationHex = generation,
            bootId = request.clock.bootId!!,
            startedElapsedRealtimeMs = request.clock.elapsedRealtimeMs,
            startedUtcMs = request.clock.utcMs,
            durationMs = request.durationMs,
            contacts = contacts
        )
        val commands = request.canonicalContactsE164.map { number ->
            TrustedContactCommand(
                e164 = number,
                command = RemotePanicCommand.build(generation, secrets.getValue(number))
            )
        }

        return when (val result = store.transaction { state ->
            if (state.phase != PanicPhase.IDLE) {
                PanicStateMutation.Keep(RemoteArmResult.Rejected(ArmRejectionReason.PANIC_ACTIVE))
            } else {
                PanicStateMutation.Replace(
                    state = state.copy(arm = armed),
                    value = RemoteArmResult.Armed(
                        commands = commands,
                        expiresUtcMs = request.clock.utcMs + request.durationMs
                    )
                )
            }
        }) {
            is PanicTransactionResult.Success -> result.value
            is PanicTransactionResult.Unavailable -> RemoteArmResult.StorageUnavailable(result.failure)
        }
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

            if (!envelope.smsPermissionObserved || !windowIsValid(arm, envelope.clock)) {
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

            PanicStateMutation.Replace(
                state = state.copy(
                    phase = PanicPhase.LOCAL_PENDING,
                    arm = null,
                    panicIdHex = panicId,
                    purgeComplete = false,
                    sessionRevocationComplete = false,
                    remoteDeleteComplete = false
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
                    state = state.copy(
                        phase = PanicPhase.LOCAL_PENDING,
                        arm = null,
                        panicIdHex = panicId,
                        purgeComplete = false,
                        sessionRevocationComplete = false,
                        remoteDeleteComplete = false
                    ),
                    value = AdmissionResult.Accepted(panicId)
                )
            }
        }) {
            is PanicTransactionResult.Success -> result.value
            is PanicTransactionResult.Unavailable -> AdmissionResult.StorageUnavailable(result.failure)
        }
    }

    private suspend fun clearExistingArmAndReject(
        reason: ArmRejectionReason
    ): RemoteArmResult =
        when (val result = store.transaction { state ->
            if (state.phase != PanicPhase.IDLE) {
                PanicStateMutation.Keep(RemoteArmResult.Rejected(ArmRejectionReason.PANIC_ACTIVE))
            } else if (state.arm == null) {
                PanicStateMutation.Keep(RemoteArmResult.Rejected(reason))
            } else {
                PanicStateMutation.Replace(
                    state.copy(arm = null),
                    RemoteArmResult.Rejected(reason)
                )
            }
        }) {
            is PanicTransactionResult.Success -> result.value
            is PanicTransactionResult.Unavailable -> RemoteArmResult.StorageUnavailable(result.failure)
        }

    private fun validateArmRequest(request: RemoteArmRequest): ArmRejectionReason? {
        if (!request.remoteChannelReady) return ArmRejectionReason.REMOTE_CHANNEL_UNAVAILABLE
        if (request.clock.bootId.isNullOrBlank() ||
            request.clock.elapsedRealtimeMs < 0 ||
            request.clock.utcMs < 0
        ) {
            return ArmRejectionReason.INVALID_CLOCK
        }
        if (request.durationMs !in RemotePanicPolicy.allowedDurationsMs) {
            return ArmRejectionReason.INVALID_DURATION
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

    private fun windowIsValid(
        arm: ArmedRemotePanic,
        now: PanicClockSnapshot
    ): Boolean {
        if (now.bootId == null || now.bootId != arm.bootId) return false
        if (now.elapsedRealtimeMs < 0 || now.utcMs < 0) return false

        val elapsedDelta = try {
            Math.subtractExact(now.elapsedRealtimeMs, arm.startedElapsedRealtimeMs)
        } catch (_: ArithmeticException) {
            return false
        }
        if (elapsedDelta < 0 || elapsedDelta >= arm.durationMs) return false

        val utcDeadline = try {
            Math.addExact(arm.startedUtcMs, arm.durationMs)
        } catch (_: ArithmeticException) {
            return false
        }
        if (now.utcMs >= utcDeadline) return false

        val utcDelta = try {
            Math.subtractExact(now.utcMs, arm.startedUtcMs)
        } catch (_: ArithmeticException) {
            return false
        }
        val drift = try {
            Math.subtractExact(utcDelta, elapsedDelta)
        } catch (_: ArithmeticException) {
            return false
        }
        return drift in -RemotePanicPolicy.DRIFT_TOLERANCE_MS..RemotePanicPolicy.DRIFT_TOLERANCE_MS
    }
}
