package org.lepotager.resiliencevault.recovery

/**
 * Metadata returned only after a recovery kit has been fully consumed, authenticated and closed.
 * Implementations must never expose kit bytes or plaintext through this contract.
 */
internal data class VerifiedExternalRecoveryKit(
    val binding: RecoveryArtifactBinding,
    val encodedBytes: Long,
    val ciphertextSha256Hex: String,
) {
    init {
        require(encodedBytes > 0)
        require(ciphertextSha256Hex.isCanonicalSha256())
    }
}

internal enum class RecoveryArchiveDeleteScope {
    OUTSIDE_ACTIVE_DELETE_SCOPE,
    INSIDE_OR_UNKNOWN,
}

/**
 * Evidence for a complete encrypted archive. The adapter must close every input before returning.
 * Completeness means all expected encrypted containers and authenticated manifest data were read.
 */
internal data class VerifiedCompleteRecoveryArchive(
    val binding: RecoveryArtifactBinding,
    val containerCount: Long,
    val ciphertextBytes: Long,
    val ciphertextSha256Hex: String,
    val deleteScope: RecoveryArchiveDeleteScope,
) {
    init {
        require(containerCount > 0)
        require(ciphertextBytes > 0)
        require(ciphertextSha256Hex.isCanonicalSha256())
    }
}

internal data class VerifiedTrialRestore(
    val binding: RecoveryArtifactBinding,
    val restoredObjectCount: Long,
) {
    init { require(restoredObjectCount > 0) }
}

internal data class VerifiedActiveVaultHead(
    val binding: RecoveryArtifactBinding,
)

/**
 * Ports are deliberately post-verification contracts: callers never receive an InputStream.
 * A production adapter must authenticate the artifact, consume it to EOF, close it, then return
 * immutable evidence. These interfaces do not define the future E/D/R crypto implementation.
 */
internal interface RecoveryKitEvidencePort {
    suspend fun inspectClosedVerifiedKit(): VerifiedExternalRecoveryKit
}

internal interface RecoveryArchiveEvidencePort {
    suspend fun inspectClosedVerifiedArchive(): VerifiedCompleteRecoveryArchive
}

internal interface RecoveryTrialRestoreEvidencePort {
    suspend fun inspectClosedVerifiedTrialRestore(): VerifiedTrialRestore
}

internal interface ActiveVaultHeadEvidencePort {
    suspend fun readVerifiedActiveHead(): VerifiedActiveVaultHead
}

/**
 * Converts typed, post-verification evidence into the existing pure preflight decision input.
 * No UI Boolean, filename or unverified manifest field can be supplied through this entry point.
 */
internal object RecoveryPreflightEvidenceAssembler {
    fun assemble(
        kit: VerifiedExternalRecoveryKit,
        archive: VerifiedCompleteRecoveryArchive,
        trial: VerifiedTrialRestore,
        active: VerifiedActiveVaultHead,
    ): RecoveryPreflightEvidence = RecoveryPreflightEvidence(
        kitReadBackVerified = true,
        archiveCompleteVerified = true,
        archiveOutsideActiveDeleteScope =
            archive.deleteScope == RecoveryArchiveDeleteScope.OUTSIDE_ACTIVE_DELETE_SCOPE,
        kitBinding = kit.binding,
        archiveBinding = archive.binding,
        trialRestoreBinding = trial.binding,
        activeBinding = active.binding,
    )
}

private fun String.isCanonicalSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
