package org.lepotager.resiliencevault.connectors.simplex

/**
 * Future cooperative SimpleX erasure is NOT implemented. This pure predicate
 * expresses necessary (not sufficient) prerequisites for an independent review.
 * It never sends a command or grants an Android permission.
 */
data class SimplexErasePreflight(
    val explicitTemporaryOwnerOptIn: Boolean,
    val requestAuthenticAndFresh: Boolean,
    val remoteBackupCommittedAndRestoredInTest: Boolean,
    val independentRecoveryKitAvailable: Boolean,
    val recoveryCopySurvivesVaultPanic: Boolean,
    val auditedCooperativeSimplexAdapter: Boolean
) {
    fun missingRequirements(): Set<SimplexEraseRequirement> = buildSet {
        if (!explicitTemporaryOwnerOptIn) add(SimplexEraseRequirement.OWNER_OPT_IN)
        if (!requestAuthenticAndFresh) add(SimplexEraseRequirement.AUTHENTIC_REQUEST)
        if (!remoteBackupCommittedAndRestoredInTest) add(SimplexEraseRequirement.RESTORED_BACKUP)
        if (!independentRecoveryKitAvailable) add(SimplexEraseRequirement.RECOVERY_KIT)
        if (!recoveryCopySurvivesVaultPanic) add(SimplexEraseRequirement.SEPARATE_RECOVERY_SCOPE)
        if (!auditedCooperativeSimplexAdapter) add(SimplexEraseRequirement.COOPERATIVE_ADAPTER)
    }
}

enum class SimplexEraseRequirement {
    OWNER_OPT_IN,
    AUTHENTIC_REQUEST,
    RESTORED_BACKUP,
    RECOVERY_KIT,
    SEPARATE_RECOVERY_SCOPE,
    COOPERATIVE_ADAPTER
}
