package org.lepotager.resiliencevault.connectors.simplex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplexErasePreflightTest {
    private val reviewed = SimplexErasePreflight(
        explicitTemporaryOwnerOptIn = true,
        requestAuthenticAndFresh = true,
        remoteBackupCommittedAndRestoredInTest = true,
        independentRecoveryKitAvailable = true,
        recoveryCopySurvivesVaultPanic = true,
        auditedCooperativeSimplexAdapter = true
    )

    @Test fun nothingIsEligibleByDefault() {
        val none = SimplexErasePreflight(false, false, false, false, false, false)
        assertEquals(SimplexEraseRequirement.entries.toSet(), none.missingRequirements())
    }

    @Test fun everyIndividualMissingPreconditionBlocksReview() {
        val cases = listOf(
            reviewed.copy(explicitTemporaryOwnerOptIn = false) to SimplexEraseRequirement.OWNER_OPT_IN,
            reviewed.copy(requestAuthenticAndFresh = false) to SimplexEraseRequirement.AUTHENTIC_REQUEST,
            reviewed.copy(remoteBackupCommittedAndRestoredInTest = false) to SimplexEraseRequirement.RESTORED_BACKUP,
            reviewed.copy(independentRecoveryKitAvailable = false) to SimplexEraseRequirement.RECOVERY_KIT,
            reviewed.copy(recoveryCopySurvivesVaultPanic = false) to SimplexEraseRequirement.SEPARATE_RECOVERY_SCOPE,
            reviewed.copy(auditedCooperativeSimplexAdapter = false) to SimplexEraseRequirement.COOPERATIVE_ADAPTER
        )
        cases.forEach { (input, expected) ->
            assertEquals(setOf(expected), input.missingRequirements())
        }
    }

    @Test fun passingNecessaryConditionsNeverExecutesErasure() {
        assertTrue(reviewed.missingRequirements().isEmpty())
        // No SimpleX erase adapter/receiver exists in the application.
    }
}
