package org.lepotager.resiliencevault.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPreflightPolicyTest {
    private val current = RecoveryArtifactBinding(
        vaultIdHex = "01".repeat(32),
        generationHex = "02".repeat(32),
        headHex = "ab".repeat(32),
    )

    private fun completeEvidence() = RecoveryPreflightEvidence(
        kitReadBackVerified = true,
        archiveCompleteVerified = true,
        archiveOutsideActiveDeleteScope = true,
        kitBinding = current,
        archiveBinding = current,
        trialRestoreBinding = current,
        activeBinding = current,
    )

    @Test fun readinessRequiresAllAuthenticatedBindingsAndExactHead() {
        assertEquals(
            RecoveryPreflightDecision.Ready(current.headHex),
            RecoveryPreflightPolicy.assess(completeEvidence()),
        )
    }

    @Test fun sameHeadFromAnotherVaultOrGenerationIsBlocked() {
        val otherVault = current.copy(vaultIdHex = "03".repeat(32))
        val otherGeneration = current.copy(generationHex = "04".repeat(32))
        val decision = RecoveryPreflightPolicy.assess(completeEvidence().copy(
            kitBinding = otherVault,
            archiveBinding = otherGeneration,
            trialRestoreBinding = otherGeneration,
        )) as RecoveryPreflightDecision.Blocked
        assertTrue(RecoveryPreflightBlockReason.KIT_BINDING_MISMATCH in decision.reasons)
        assertTrue(RecoveryPreflightBlockReason.ARCHIVE_BINDING_MISMATCH in decision.reasons)
        assertTrue(RecoveryPreflightBlockReason.TRIAL_RESTORE_STALE in decision.reasons)
    }

    @Test fun newManifestHeadInvalidatesPreviouslyCompleteTrialAndArchive() {
        val decision = RecoveryPreflightPolicy.assess(completeEvidence().copy(
            activeBinding = current.copy(headHex = "cd".repeat(32)),
        )) as RecoveryPreflightDecision.Blocked
        assertTrue(RecoveryPreflightBlockReason.KIT_BINDING_MISMATCH in decision.reasons)
        assertTrue(RecoveryPreflightBlockReason.ARCHIVE_BINDING_MISMATCH in decision.reasons)
        assertTrue(RecoveryPreflightBlockReason.TRIAL_RESTORE_STALE in decision.reasons)
    }

    @Test fun booleanClaimsCannotReplaceMissingAuthenticatedArtifacts() {
        val decision = RecoveryPreflightPolicy.assess(completeEvidence().copy(
            kitBinding = null,
            archiveBinding = null,
            trialRestoreBinding = null,
        )) as RecoveryPreflightDecision.Blocked
        assertTrue(RecoveryPreflightBlockReason.KIT_BINDING_MISSING in decision.reasons)
        assertTrue(RecoveryPreflightBlockReason.ARCHIVE_BINDING_MISSING in decision.reasons)
        assertTrue(RecoveryPreflightBlockReason.TRIAL_RESTORE_MISSING in decision.reasons)
    }

    @Test fun exportedKitAloneDoesNotClaimRecoverability() {
        val decision = RecoveryPreflightPolicy.assess(completeEvidence().copy(
            archiveCompleteVerified = false,
            archiveOutsideActiveDeleteScope = false,
            archiveBinding = null,
            trialRestoreBinding = null,
            activeBinding = null,
        )) as RecoveryPreflightDecision.Blocked
        assertTrue(RecoveryPreflightBlockReason.ARCHIVE_NOT_COMPLETE in decision.reasons)
        assertTrue(RecoveryPreflightBlockReason.ARCHIVE_INSIDE_DELETE_SCOPE in decision.reasons)
        assertTrue(RecoveryPreflightBlockReason.ACTIVE_HEAD_UNKNOWN in decision.reasons)
    }
}
