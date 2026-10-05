package org.lepotager.resiliencevault.recovery

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPreflightInspectorTest {
    private val first = RecoveryArtifactBinding("a".repeat(64), "b".repeat(64), "c".repeat(64))
    private val advanced = first.copy(headHex = "d".repeat(64))

    private fun inspector(heads: List<RecoveryArtifactBinding>): RecoveryPreflightInspector {
        var reads = 0
        return RecoveryPreflightInspector(
            kit = object : RecoveryKitEvidencePort {
                override suspend fun inspectClosedVerifiedKit() =
                    VerifiedExternalRecoveryKit(first, 1, "e".repeat(64))
            },
            archive = object : RecoveryArchiveEvidencePort {
                override suspend fun inspectClosedVerifiedArchive() =
                    VerifiedCompleteRecoveryArchive(
                        first, 1, 1, "f".repeat(64),
                        RecoveryArchiveDeleteScope.OUTSIDE_ACTIVE_DELETE_SCOPE
                    )
            },
            trial = object : RecoveryTrialRestoreEvidencePort {
                override suspend fun inspectClosedVerifiedTrialRestore() =
                    VerifiedTrialRestore(first, 1)
            },
            active = object : ActiveVaultHeadEvidencePort {
                override suspend fun readVerifiedActiveHead() =
                    VerifiedActiveVaultHead(heads[reads++])
            },
        )
    }

    @Test fun stableHeadAllowsVerifiedPreflight() = runTest {
        assertEquals(
            RecoveryPreflightDecision.Ready(first.headHex),
            inspector(listOf(first, first)).inspect()
        )
    }

    @Test fun headAdvancedDuringInspectionBlocks() = runTest {
        val decision = inspector(listOf(first, advanced)).inspect()
        assertTrue(decision is RecoveryPreflightDecision.Blocked)
        assertTrue(
            RecoveryPreflightBlockReason.TRIAL_RESTORE_STALE in
                (decision as RecoveryPreflightDecision.Blocked).reasons
        )
    }
}
