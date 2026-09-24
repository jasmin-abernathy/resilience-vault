package org.lepotager.resiliencevault.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPreflightPolicyTest {
    private val head = "ab".repeat(32)

    @Test
    fun readiness_requires_verified_kit_archive_independent_scope_and_exact_head() {
        val decision = RecoveryPreflightPolicy.assess(
            RecoveryPreflightEvidence(
                kitReadBackVerified = true,
                archiveCompleteVerified = true,
                archiveOutsideActiveDeleteScope = true,
                trialRestoreHeadHex = head,
                activeHeadHex = head,
            )
        )
        assertEquals(RecoveryPreflightDecision.Ready(head), decision)
    }

    @Test
    fun stale_trial_is_blocked_even_when_every_other_signal_is_true() {
        val result = RecoveryPreflightPolicy.assess(
            RecoveryPreflightEvidence(
                kitReadBackVerified = true,
                archiveCompleteVerified = true,
                archiveOutsideActiveDeleteScope = true,
                trialRestoreHeadHex = "cd".repeat(32),
                activeHeadHex = head,
            )
        )
        assertTrue(
            (result as RecoveryPreflightDecision.Blocked).reasons.contains(
                RecoveryPreflightBlockReason.TRIAL_RESTORE_STALE
            )
        )
    }

    @Test
    fun boolean_kit_export_alone_can_never_claim_recoverability() {
        val result = RecoveryPreflightPolicy.assess(
            RecoveryPreflightEvidence(
                kitReadBackVerified = true,
                archiveCompleteVerified = false,
                archiveOutsideActiveDeleteScope = false,
                trialRestoreHeadHex = null,
                activeHeadHex = null,
            )
        ) as RecoveryPreflightDecision.Blocked
        assertTrue(result.reasons.contains(RecoveryPreflightBlockReason.ARCHIVE_NOT_COMPLETE))
        assertTrue(result.reasons.contains(RecoveryPreflightBlockReason.TRIAL_RESTORE_MISSING))
        assertTrue(result.reasons.contains(RecoveryPreflightBlockReason.ACTIVE_HEAD_UNKNOWN))
    }
}
