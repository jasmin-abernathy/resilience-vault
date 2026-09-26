package org.lepotager.resiliencevault.cloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RemoteDeleteProvisioningSafetyTest : RemoteDeleteProvisioningTestFixture() {
    @Test
    fun capsule_must_match_exact_identity_digest_and_local_capability() {
        val prepared = prepared()
        val confirmed = (
            RemoteDeleteProvisioningStateMachine.observeServer(
                prepared,
                RemoteDeleteProvisioningServerObservation.Confirmed(
                    provisionedBinding(prepared),
                    RemoteDeleteProvisioningServerState.PROVISIONED,
                ),
            ) as RemoteDeleteProvisioningDecision.Advance
        ).journal

        val wrong = RemoteDeleteProvisioningStateMachine.verifyCapsule(
            confirmed,
            RemoteDeleteProvisioningCapsuleEvidence(
                identity = identity,
                capsuleDigestHex = "7".repeat(64),
                capsuleReadable = true,
                deleteKekPresent = true,
            ),
        )
        assertTrue(wrong is RemoteDeleteProvisioningDecision.Blocked)

        val verified = RemoteDeleteProvisioningStateMachine.verifyCapsule(
            confirmed,
            RemoteDeleteProvisioningCapsuleEvidence(
                identity = identity,
                capsuleDigestHex = confirmed.capsuleDigestHex,
                capsuleReadable = true,
                deleteKekPresent = true,
            ),
        ) as RemoteDeleteProvisioningDecision.Advance
        assertEquals(
            RemoteDeleteProvisioningPhase.CAPSULE_VERIFIED,
            verified.journal.phase,
        )
    }

    @Test
    fun authority_publish_requires_exact_blocked_revision_and_idle_unarmed_panic() {
        val verified = fullyCapsuleVerified()

        val changedAuthority = RemoteDeleteProvisioningStateMachine.publishAuthority(
            verified,
            RemoteDeleteProvisioningAuthorityEvidence(
                identity = identity,
                blockedAuthorityRevision = verified.authorityRevisionAtBegin + 2,
                blockedTransition = true,
                panicIdle = true,
                armAbsent = true,
            ),
        )
        assertEquals(
            RemoteDeleteProvisioningBlockReason.AUTHORITY_CHANGED,
            (changedAuthority as RemoteDeleteProvisioningDecision.Blocked).reason,
        )

        val panic = RemoteDeleteProvisioningStateMachine.publishAuthority(
            verified,
            RemoteDeleteProvisioningAuthorityEvidence(
                identity = identity,
                blockedAuthorityRevision = verified.authorityRevisionAtBegin + 1,
                blockedTransition = true,
                panicIdle = false,
                armAbsent = true,
            ),
        )
        assertEquals(
            RemoteDeleteProvisioningBlockReason.PANIC_OR_ARM_ACTIVE,
            (panic as RemoteDeleteProvisioningDecision.Blocked).reason,
        )

        val published = RemoteDeleteProvisioningStateMachine.publishAuthority(
            verified,
            RemoteDeleteProvisioningAuthorityEvidence(
                identity = identity,
                blockedAuthorityRevision = verified.authorityRevisionAtBegin + 1,
                blockedTransition = true,
                panicIdle = true,
                armAbsent = true,
            ),
        ) as RemoteDeleteProvisioningDecision.Advance
        assertEquals(
            RemoteDeleteProvisioningPhase.AUTHORITY_PUBLISHED,
            published.journal.phase,
        )
    }

    @Test
    fun panic_during_network_abandons_and_late_success_cannot_resurrect_publication() {
        val journal = prepared()
        val abandoned = RemoteDeleteProvisioningStateMachine.abandonForReconciliation(journal)
            as RemoteDeleteProvisioningDecision.Advance
        assertEquals(
            RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION,
            abandoned.journal.phase,
        )

        val late = RemoteDeleteProvisioningStateMachine.observeServer(
            abandoned.journal,
            RemoteDeleteProvisioningServerObservation.Confirmed(
                provisionedBinding(journal),
                RemoteDeleteProvisioningServerState.PROVISIONED,
            ),
        ) as RemoteDeleteProvisioningDecision.Advance
        assertEquals(
            RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION,
            late.journal.phase,
        )
        assertEquals(
            RemoteDeleteProvisioningServerState.PROVISIONED,
            late.journal.lastConfirmedServerState,
        )
        assertTrue(
            RemoteDeleteProvisioningStateMachine.publishAuthority(
                late.journal,
                RemoteDeleteProvisioningAuthorityEvidence(
                    identity = identity,
                    blockedAuthorityRevision = journal.authorityRevisionAtBegin + 1,
                    blockedTransition = true,
                    panicIdle = true,
                    armAbsent = true,
                ),
            ) is RemoteDeleteProvisioningDecision.Blocked
        )
    }

    @Test
    fun rotation_concurrent_revision_change_blocks_configured_publication() {
        val verified = fullyCapsuleVerified()
        val decision = RemoteDeleteProvisioningStateMachine.publishAuthority(
            verified,
            RemoteDeleteProvisioningAuthorityEvidence(
                identity = identity,
                blockedAuthorityRevision = verified.authorityRevisionAtBegin + 2,
                blockedTransition = true,
                panicIdle = true,
                armAbsent = true,
            ),
        ) as RemoteDeleteProvisioningDecision.Blocked

        assertEquals(RemoteDeleteProvisioningBlockReason.AUTHORITY_CHANGED, decision.reason)
        assertEquals(verified, decision.journal)
    }
}
