package org.lepotager.resiliencevault.recovery

import java.io.InputStream

/** Each result follows complete archive authentication and input closure. */
internal class TinkRecoveryEvidenceAdapter(
    private val kitSource: () -> InputStream,
    private val archiveSource: () -> InputStream,
    private val expected: RecoveryArtifactBinding,
    // Trusted storage policy, never archive-provided metadata or a UI checkbox.
    private val scope: RecoveryArchiveDeleteScope,
) : RecoveryKitEvidencePort, RecoveryArchiveEvidencePort, RecoveryTrialRestoreEvidencePort {
    private fun verify() = TinkRecoveryArchive.verify(kitSource, archiveSource, expected, scope)
    override suspend fun inspectClosedVerifiedKit(): VerifiedExternalRecoveryKit = verify().use { it.kitEvidence }
    override suspend fun inspectClosedVerifiedArchive(): VerifiedCompleteRecoveryArchive = verify().use { it.archiveEvidence }
    override suspend fun inspectClosedVerifiedTrialRestore(): VerifiedTrialRestore = verify().use { it.trial() }
}
