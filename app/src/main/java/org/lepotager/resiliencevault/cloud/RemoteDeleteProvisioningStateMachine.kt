package org.lepotager.resiliencevault.cloud

internal object RemoteDeleteProvisioningStateMachine {
    fun observeServer(
        journal: RemoteDeleteProvisioningJournal,
        observation: RemoteDeleteProvisioningServerObservation,
    ): RemoteDeleteProvisioningDecision {
        if (journal.phase == RemoteDeleteProvisioningPhase.AUTHORITY_PUBLISHED) {
            return RemoteDeleteProvisioningDecision.Blocked(
                RemoteDeleteProvisioningBlockReason.ALREADY_PUBLISHED,
                journal,
            )
        }

        return when (observation) {
            is RemoteDeleteProvisioningServerObservation.Unknown ->
                RemoteDeleteProvisioningDecision.Keep(journal)

            is RemoteDeleteProvisioningServerObservation.Conflict -> {
                if (observation.operationIdHex != journal.operationIdHex) {
                    RemoteDeleteProvisioningDecision.Blocked(
                        RemoteDeleteProvisioningBlockReason.BINDING_MISMATCH,
                        journal,
                    )
                } else if (
                    observation.expectedRequestDigestHex != journal.requestDigestHex ||
                    observation.observedRequestDigestHex == journal.requestDigestHex
                ) {
                    RemoteDeleteProvisioningDecision.Blocked(
                        RemoteDeleteProvisioningBlockReason.BINDING_MISMATCH,
                        journal,
                    )
                } else if (
                    journal.lastConfirmedServerState !=
                        RemoteDeleteProvisioningServerState.UNCONFIRMED
                ) {
                    RemoteDeleteProvisioningDecision.Blocked(
                        RemoteDeleteProvisioningBlockReason.REQUEST_DIGEST_CONFLICT,
                        journal,
                    )
                } else {
                    advance(
                        journal,
                        journal.copy(
                            phase = RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION,
                            lastConfirmedServerState =
                                RemoteDeleteProvisioningServerState.CONFLICT,
                        )
                    )
                }
            }

            is RemoteDeleteProvisioningServerObservation.Confirmed -> {
                if (!bindingMatches(journal, observation.binding)) {
                    return RemoteDeleteProvisioningDecision.Blocked(
                        RemoteDeleteProvisioningBlockReason.BINDING_MISMATCH,
                        journal,
                    )
                }

                if (journal.phase == RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION) {
                    if (
                        journal.lastConfirmedServerState ==
                            RemoteDeleteProvisioningServerState.CONFLICT
                    ) {
                        return RemoteDeleteProvisioningDecision.Blocked(
                            RemoteDeleteProvisioningBlockReason.REQUEST_DIGEST_CONFLICT,
                            journal,
                        )
                    }
                    val nextState = when (observation.state) {
                        RemoteDeleteProvisioningServerState.PROVISIONED ->
                            RemoteDeleteProvisioningServerState.PROVISIONED
                        RemoteDeleteProvisioningServerState.REVOKED ->
                            RemoteDeleteProvisioningServerState.REVOKED
                        else -> error("Unreachable confirmed server state")
                    }
                    val next = journal.copy(lastConfirmedServerState = nextState)
                    return if (next == journal) {
                        RemoteDeleteProvisioningDecision.Keep(journal)
                    } else {
                        advance(journal, next)
                    }
                }

                if (observation.state != RemoteDeleteProvisioningServerState.PROVISIONED) {
                    return RemoteDeleteProvisioningDecision.Blocked(
                        RemoteDeleteProvisioningBlockReason.UNEXPECTED_SERVER_STATE,
                        journal,
                    )
                }

                when (journal.phase) {
                    RemoteDeleteProvisioningPhase.PREPARED ->
                        advance(
                            journal,
                            journal.copy(
                                phase = RemoteDeleteProvisioningPhase.SERVER_CONFIRMED,
                                lastConfirmedServerState =
                                    RemoteDeleteProvisioningServerState.PROVISIONED,
                            )
                        )

                    RemoteDeleteProvisioningPhase.SERVER_CONFIRMED,
                    RemoteDeleteProvisioningPhase.CAPSULE_VERIFIED ->
                        RemoteDeleteProvisioningDecision.Keep(journal)

                    RemoteDeleteProvisioningPhase.AUTHORITY_PUBLISHED ->
                        RemoteDeleteProvisioningDecision.Blocked(
                            RemoteDeleteProvisioningBlockReason.ALREADY_PUBLISHED,
                            journal,
                        )

                    RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION ->
                        error("Handled above")
                }
            }
        }
    }

    fun verifyCapsule(
        journal: RemoteDeleteProvisioningJournal,
        evidence: RemoteDeleteProvisioningCapsuleEvidence,
    ): RemoteDeleteProvisioningDecision {
        if (journal.phase != RemoteDeleteProvisioningPhase.SERVER_CONFIRMED) {
            return RemoteDeleteProvisioningDecision.Blocked(
                RemoteDeleteProvisioningBlockReason.WRONG_PHASE,
                journal,
            )
        }
        if (
            evidence.identity != journal.identity ||
            evidence.capsuleDigestHex != journal.capsuleDigestHex ||
            !evidence.capsuleReadable ||
            !evidence.deleteKekPresent
        ) {
            return RemoteDeleteProvisioningDecision.Blocked(
                RemoteDeleteProvisioningBlockReason.CAPSULE_UNVERIFIED,
                journal,
            )
        }
        return advance(
            journal,
            journal.copy(phase = RemoteDeleteProvisioningPhase.CAPSULE_VERIFIED),
        )
    }

    fun publishAuthority(
        journal: RemoteDeleteProvisioningJournal,
        evidence: RemoteDeleteProvisioningAuthorityEvidence,
    ): RemoteDeleteProvisioningDecision {
        if (journal.phase != RemoteDeleteProvisioningPhase.CAPSULE_VERIFIED) {
            return RemoteDeleteProvisioningDecision.Blocked(
                RemoteDeleteProvisioningBlockReason.WRONG_PHASE,
                journal,
            )
        }
        if (!evidence.panicIdle || !evidence.armAbsent) {
            return RemoteDeleteProvisioningDecision.Blocked(
                RemoteDeleteProvisioningBlockReason.PANIC_OR_ARM_ACTIVE,
                journal,
            )
        }
        if (
            evidence.identity != journal.identity ||
            !evidence.blockedTransition ||
            evidence.blockedAuthorityRevision != journal.authorityRevisionAtBegin + 1L
        ) {
            return RemoteDeleteProvisioningDecision.Blocked(
                RemoteDeleteProvisioningBlockReason.AUTHORITY_CHANGED,
                journal,
            )
        }
        return advance(
            journal,
            journal.copy(phase = RemoteDeleteProvisioningPhase.AUTHORITY_PUBLISHED),
        )
    }

    fun abandonForReconciliation(
        journal: RemoteDeleteProvisioningJournal,
    ): RemoteDeleteProvisioningDecision {
        if (journal.phase == RemoteDeleteProvisioningPhase.AUTHORITY_PUBLISHED) {
            return RemoteDeleteProvisioningDecision.Blocked(
                RemoteDeleteProvisioningBlockReason.ALREADY_PUBLISHED,
                journal,
            )
        }
        if (journal.phase == RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION) {
            return RemoteDeleteProvisioningDecision.Keep(journal)
        }
        return advance(
            journal,
            journal.copy(
                phase = RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION,
            ),
        )
    }

    private fun advance(
        previous: RemoteDeleteProvisioningJournal,
        next: RemoteDeleteProvisioningJournal,
    ): RemoteDeleteProvisioningDecision.Advance {
        next.validateTransitionFrom(previous)
        return RemoteDeleteProvisioningDecision.Advance(next)
    }

    private fun bindingMatches(
        journal: RemoteDeleteProvisioningJournal,
        binding: RemoteDeleteProvisioningBinding,
    ): Boolean =
        binding.operationIdHex == journal.operationIdHex &&
            binding.identity == journal.identity &&
            binding.requestDigestHex == journal.requestDigestHex
}
