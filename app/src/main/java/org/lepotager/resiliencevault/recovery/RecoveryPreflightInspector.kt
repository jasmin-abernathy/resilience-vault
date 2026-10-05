package org.lepotager.resiliencevault.recovery

/**
 * Collects post-verification evidence and rejects a manifest head that changes during inspection.
 * A Ready result is a preflight snapshot only: the eventual destructive or activation operation
 * must revalidate the head while holding its own exclusive vault mutation guard.
 */
internal class RecoveryPreflightInspector(
    private val kit: RecoveryKitEvidencePort,
    private val archive: RecoveryArchiveEvidencePort,
    private val trial: RecoveryTrialRestoreEvidencePort,
    private val active: ActiveVaultHeadEvidencePort,
) {
    suspend fun inspect(): RecoveryPreflightDecision {
        val before = active.readVerifiedActiveHead()
        val verifiedKit = kit.inspectClosedVerifiedKit()
        val verifiedArchive = archive.inspectClosedVerifiedArchive()
        val verifiedTrial = trial.inspectClosedVerifiedTrialRestore()
        val after = active.readVerifiedActiveHead()
        if (before.binding != after.binding) {
            return RecoveryPreflightDecision.Blocked(
                setOf(RecoveryPreflightBlockReason.TRIAL_RESTORE_STALE)
            )
        }
        return RecoveryPreflightPolicy.assess(
            RecoveryPreflightEvidenceAssembler.assemble(
                verifiedKit, verifiedArchive, verifiedTrial, after
            )
        )
    }
}
