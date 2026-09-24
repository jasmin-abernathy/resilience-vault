package org.lepotager.resiliencevault.recovery

/**
 * Pure decision contract only. Booleans here must eventually be derived from authenticated
 * artifacts and a completed restore exercise; UI state alone is never sufficient evidence.
 */
data class RecoveryPreflightEvidence(
    val kitReadBackVerified: Boolean,
    val archiveCompleteVerified: Boolean,
    val archiveOutsideActiveDeleteScope: Boolean,
    val trialRestoreHeadHex: String?,
    val activeHeadHex: String?,
) {
    init {
        listOfNotNull(trialRestoreHeadHex, activeHeadHex).forEach { head ->
            require(head.length == 64 && head.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }
}

enum class RecoveryPreflightBlockReason {
    KIT_NOT_VERIFIED,
    ARCHIVE_NOT_COMPLETE,
    ARCHIVE_INSIDE_DELETE_SCOPE,
    ACTIVE_HEAD_UNKNOWN,
    TRIAL_RESTORE_MISSING,
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
        val active = evidence.activeHeadHex
        val trial = evidence.trialRestoreHeadHex
        if (active == null) reasons += RecoveryPreflightBlockReason.ACTIVE_HEAD_UNKNOWN
        if (trial == null) reasons += RecoveryPreflightBlockReason.TRIAL_RESTORE_MISSING
        if (active != null && trial != null && active != trial) {
            reasons += RecoveryPreflightBlockReason.TRIAL_RESTORE_STALE
        }
        return if (reasons.isEmpty()) {
            RecoveryPreflightDecision.Ready(checkNotNull(active))
        } else {
            RecoveryPreflightDecision.Blocked(reasons)
        }
    }
}
