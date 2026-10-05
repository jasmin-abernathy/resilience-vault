package org.lepotager.resiliencevault.recovery

/**
 * Identity and manifest head obtained from an authenticated artifact, not from a filename,
 * UI checkbox, archive-provided vault ID or unverified JSON field.
 */
data class RecoveryArtifactBinding(
    val vaultIdHex: String,
    val generationHex: String,
    val headHex: String,
) {
    init {
        listOf(vaultIdHex, generationHex, headHex).forEach { value ->
            require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }
}

/**
 * Pure decision contract only. The caller must authenticate the kit, the complete archive,
 * and the trial restore before providing their bindings. No Boolean or matching string proves
 * that this happened. A new active manifest head invalidates the previous trial.
 */
data class RecoveryPreflightEvidence(
    val kitReadBackVerified: Boolean,
    val archiveCompleteVerified: Boolean,
    val archiveOutsideActiveDeleteScope: Boolean,
    val kitBinding: RecoveryArtifactBinding?,
    val archiveBinding: RecoveryArtifactBinding?,
    val trialRestoreBinding: RecoveryArtifactBinding?,
    val activeBinding: RecoveryArtifactBinding?,
)

enum class RecoveryPreflightBlockReason {
    KIT_NOT_VERIFIED,
    ARCHIVE_NOT_COMPLETE,
    ARCHIVE_INSIDE_DELETE_SCOPE,
    ACTIVE_HEAD_UNKNOWN,
    KIT_BINDING_MISSING,
    ARCHIVE_BINDING_MISSING,
    TRIAL_RESTORE_MISSING,
    KIT_BINDING_MISMATCH,
    ARCHIVE_BINDING_MISMATCH,
    TRIAL_RESTORE_STALE,
}

sealed interface RecoveryPreflightDecision {
    data class Ready(val verifiedHeadHex: String) : RecoveryPreflightDecision
    data class Blocked(
        val reasons: Set<RecoveryPreflightBlockReason>
    ) : RecoveryPreflightDecision
}

object RecoveryPreflightPolicy {
    fun assess(evidence: RecoveryPreflightEvidence): RecoveryPreflightDecision {
        val reasons = linkedSetOf<RecoveryPreflightBlockReason>()
        if (!evidence.kitReadBackVerified) {
            reasons += RecoveryPreflightBlockReason.KIT_NOT_VERIFIED
        }
        if (!evidence.archiveCompleteVerified) {
            reasons += RecoveryPreflightBlockReason.ARCHIVE_NOT_COMPLETE
        }
        if (!evidence.archiveOutsideActiveDeleteScope) {
            reasons += RecoveryPreflightBlockReason.ARCHIVE_INSIDE_DELETE_SCOPE
        }
        val active = evidence.activeBinding
        val kit = evidence.kitBinding
        val archive = evidence.archiveBinding
        val trial = evidence.trialRestoreBinding
        if (active == null) reasons += RecoveryPreflightBlockReason.ACTIVE_HEAD_UNKNOWN
        if (kit == null) reasons += RecoveryPreflightBlockReason.KIT_BINDING_MISSING
        if (archive == null) reasons += RecoveryPreflightBlockReason.ARCHIVE_BINDING_MISSING
        if (trial == null) reasons += RecoveryPreflightBlockReason.TRIAL_RESTORE_MISSING
        if (active != null) {
            if (kit != null && kit != active) {
                reasons += RecoveryPreflightBlockReason.KIT_BINDING_MISMATCH
            }
            if (archive != null && archive != active) {
                reasons += RecoveryPreflightBlockReason.ARCHIVE_BINDING_MISMATCH
            }
            if (trial != null && trial != active) {
                reasons += RecoveryPreflightBlockReason.TRIAL_RESTORE_STALE
            }
        }
        return if (reasons.isEmpty()) {
            RecoveryPreflightDecision.Ready(checkNotNull(active).headHex)
        } else {
            RecoveryPreflightDecision.Blocked(reasons)
        }
    }
}
