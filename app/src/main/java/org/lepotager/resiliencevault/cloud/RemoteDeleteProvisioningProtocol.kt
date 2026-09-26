package org.lepotager.resiliencevault.cloud

data class RemoteDeleteProvisioningBinding(
    val operationIdHex: String,
    val identity: RemoteDeleteProvisioningIdentity,
    val requestDigestHex: String,
) {
    init {
        require(RemoteDeleteProvisioningIdentity.isLowerHex(operationIdHex, 64))
        require(RemoteDeleteProvisioningIdentity.isLowerHex(requestDigestHex, 64))
    }

    override fun toString(): String = "RemoteDeleteProvisioningBinding([redacted])"
}

internal enum class RemoteDeleteProvisioningUnknownReason {
    TIMEOUT,
    NO_NETWORK,
    RESPONSE_UNVERIFIED,
}

internal sealed interface RemoteDeleteProvisioningServerObservation {
    data class Unknown(val reason: RemoteDeleteProvisioningUnknownReason) :
        RemoteDeleteProvisioningServerObservation

    data class Confirmed(
        val binding: RemoteDeleteProvisioningBinding,
        val state: RemoteDeleteProvisioningServerState,
    ) : RemoteDeleteProvisioningServerObservation {
        init {
            require(
                state == RemoteDeleteProvisioningServerState.PROVISIONED ||
                    state == RemoteDeleteProvisioningServerState.REVOKED
            )
        }

        override fun toString(): String =
            "RemoteDeleteProvisioningServerObservation.Confirmed(state=" + state + ", [redacted])"
    }

    data class Conflict(
        val operationIdHex: String,
        val expectedRequestDigestHex: String,
        val observedRequestDigestHex: String,
    ) : RemoteDeleteProvisioningServerObservation {
        init {
            require(RemoteDeleteProvisioningIdentity.isLowerHex(operationIdHex, 64))
            require(RemoteDeleteProvisioningIdentity.isLowerHex(expectedRequestDigestHex, 64))
            require(RemoteDeleteProvisioningIdentity.isLowerHex(observedRequestDigestHex, 64))
            require(expectedRequestDigestHex != observedRequestDigestHex)
        }

        override fun toString(): String =
            "RemoteDeleteProvisioningServerObservation.Conflict([redacted])"
    }
}

internal data class RemoteDeleteProvisioningCapsuleEvidence(
    val identity: RemoteDeleteProvisioningIdentity,
    val capsuleDigestHex: String,
    val capsuleReadable: Boolean,
    val deleteKekPresent: Boolean,
) {
    init {
        require(RemoteDeleteProvisioningIdentity.isLowerHex(capsuleDigestHex, 64))
    }
}

internal data class RemoteDeleteProvisioningAuthorityEvidence(
    val identity: RemoteDeleteProvisioningIdentity,
    val blockedAuthorityRevision: Long,
    val blockedTransition: Boolean,
    val panicIdle: Boolean,
    val armAbsent: Boolean,
) {
    init {
        require(blockedAuthorityRevision > 0)
    }
}

internal enum class RemoteDeleteProvisioningBlockReason {
    BINDING_MISMATCH,
    REQUEST_DIGEST_CONFLICT,
    UNEXPECTED_SERVER_STATE,
    WRONG_PHASE,
    CAPSULE_UNVERIFIED,
    AUTHORITY_CHANGED,
    PANIC_OR_ARM_ACTIVE,
    ALREADY_PUBLISHED,
}

internal sealed interface RemoteDeleteProvisioningDecision {
    data class Advance(val journal: RemoteDeleteProvisioningJournal) :
        RemoteDeleteProvisioningDecision
    data class Keep(val journal: RemoteDeleteProvisioningJournal) :
        RemoteDeleteProvisioningDecision
    data class Blocked(
        val reason: RemoteDeleteProvisioningBlockReason,
        val journal: RemoteDeleteProvisioningJournal,
    ) : RemoteDeleteProvisioningDecision
}
