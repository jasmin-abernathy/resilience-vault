package org.lepotager.resiliencevault.recovery

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEvidencePortsTest {
    private val binding = RecoveryArtifactBinding(
        vaultIdHex = "11".repeat(32),
        generationHex = "22".repeat(32),
        headHex = "33".repeat(32),
    )

    @Test
    fun typedEvidenceBuildsReadyPreflightWithoutUiClaims() = runTest {
        val kit = FakeKitPort(VerifiedExternalRecoveryKit(binding, 128, "44".repeat(32)))
        val archive = FakeArchivePort(VerifiedCompleteRecoveryArchive(
            binding = binding,
            containerCount = 3,
            ciphertextBytes = 4096,
            ciphertextSha256Hex = "55".repeat(32),
            deleteScope = RecoveryArchiveDeleteScope.OUTSIDE_ACTIVE_DELETE_SCOPE,
        ))
        val trial = FakeTrialPort(VerifiedTrialRestore(binding, 3))
        val active = FakeActivePort(VerifiedActiveVaultHead(binding))

        val evidence = RecoveryPreflightEvidenceAssembler.assemble(
            kit.inspectClosedVerifiedKit(),
            archive.inspectClosedVerifiedArchive(),
            trial.inspectClosedVerifiedTrialRestore(),
            active.readVerifiedActiveHead(),
        )

        assertEquals(
            RecoveryPreflightDecision.Ready(binding.headHex),
            RecoveryPreflightPolicy.assess(evidence)
        )
    }

    @Test
    fun typedArchiveInsideDeleteScopeStillBlocksRecoveryClaim() = runTest {
        val evidence = RecoveryPreflightEvidenceAssembler.assemble(
            VerifiedExternalRecoveryKit(binding, 128, "44".repeat(32)),
            VerifiedCompleteRecoveryArchive(
                binding, 3, 4096, "55".repeat(32), RecoveryArchiveDeleteScope.INSIDE_OR_UNKNOWN
            ),
            VerifiedTrialRestore(binding, 3),
            VerifiedActiveVaultHead(binding),
        )

        val result = RecoveryPreflightPolicy.assess(evidence) as RecoveryPreflightDecision.Blocked
        assertTrue(RecoveryPreflightBlockReason.ARCHIVE_INSIDE_DELETE_SCOPE in result.reasons)
    }

    private class FakeKitPort(
        private val value: VerifiedExternalRecoveryKit
    ) : RecoveryKitEvidencePort {
        override suspend fun inspectClosedVerifiedKit() = value
    }

    private class FakeArchivePort(
        private val value: VerifiedCompleteRecoveryArchive
    ) : RecoveryArchiveEvidencePort {
        override suspend fun inspectClosedVerifiedArchive() = value
    }

    private class FakeTrialPort(
        private val value: VerifiedTrialRestore
    ) : RecoveryTrialRestoreEvidencePort {
        override suspend fun inspectClosedVerifiedTrialRestore() = value
    }

    private class FakeActivePort(
        private val value: VerifiedActiveVaultHead
    ) : ActiveVaultHeadEvidencePort {
        override suspend fun readVerifiedActiveHead() = value
    }
}
