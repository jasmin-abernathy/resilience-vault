package org.lepotager.resiliencevault.recovery

import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPreflightAdversarialTest {
    private val oldGeneration = RecoveryArtifactBinding(
        vaultIdHex = "01".repeat(32),
        generationHex = "02".repeat(32),
        headHex = "aa".repeat(32),
    )

    private val current = RecoveryArtifactBinding(
        vaultIdHex = "01".repeat(32),
        generationHex = "03".repeat(32),
        headHex = "bb".repeat(32),
    )

    private fun evidence(
        kit: RecoveryArtifactBinding = current,
        archive: RecoveryArtifactBinding = current,
        trial: RecoveryArtifactBinding = current,
        active: RecoveryArtifactBinding = current,
        archiveComplete: Boolean = true,
        outsideDeleteScope: Boolean = true,
    ) = RecoveryPreflightEvidence(
        kitReadBackVerified = true,
        archiveCompleteVerified = archiveComplete,
        archiveOutsideActiveDeleteScope = outsideDeleteScope,
        kitBinding = kit,
        archiveBinding = archive,
        trialRestoreBinding = trial,
        activeBinding = active,
    )

    @Test
    fun oldKitAfterGenerationRotationDoesNotAuthorizeCurrentArchive() {
        val result = RecoveryPreflightPolicy.assess(
            evidence(kit = oldGeneration)
        ) as RecoveryPreflightDecision.Blocked

        assertTrue(RecoveryPreflightBlockReason.KIT_BINDING_MISMATCH in result.reasons)
    }

    @Test
    fun matchingBindingsDoNotOverrideIncompleteOrDeleteScopedArchive() {
        val result = RecoveryPreflightPolicy.assess(
            evidence(archiveComplete = false, outsideDeleteScope = false)
        ) as RecoveryPreflightDecision.Blocked

        assertTrue(RecoveryPreflightBlockReason.ARCHIVE_NOT_COMPLETE in result.reasons)
        assertTrue(RecoveryPreflightBlockReason.ARCHIVE_INSIDE_DELETE_SCOPE in result.reasons)
    }

    @Test
    fun trialForPreviousHeadExpiresWhenOnlyActiveHeadAdvances() {
        val oldHead = current.copy(headHex = "cc".repeat(32))
        val result = RecoveryPreflightPolicy.assess(
            evidence(trial = oldHead)
        ) as RecoveryPreflightDecision.Blocked

        assertTrue(RecoveryPreflightBlockReason.TRIAL_RESTORE_STALE in result.reasons)
    }
}
