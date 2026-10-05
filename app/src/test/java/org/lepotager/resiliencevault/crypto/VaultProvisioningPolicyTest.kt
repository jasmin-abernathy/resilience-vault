package org.lepotager.resiliencevault.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultProvisioningPolicyTest {
    private val empty = VaultProvisioningPolicy.Evidence(
        knownVault = false, journalPhase = null, journalCorrupt = false,
        inventoryPresent = false, inventoryValid = false,
        epochEnvelopePresent = false, keystoreAliasPresent = false
    )

    @Test fun explicitFreshCreationOnlyWhenNoTracesExist() {
        assertEquals(VaultProvisioningPolicy.Decision.CREATE_NEW,
            VaultProvisioningPolicy.decideFreshCreation(empty))
        assertEquals(VaultProvisioningPolicy.Decision.BLOCK_NOT_NEW,
            VaultProvisioningPolicy.decideOpen(empty))
        for (candidate in listOf(
            empty.copy(knownVault = true),
            empty.copy(journalPhase = VaultProvisioningPolicy.JournalPhase.BEGIN),
            empty.copy(inventoryPresent = true),
            empty.copy(epochEnvelopePresent = true),
            empty.copy(keystoreAliasPresent = true)
        )) {
            assertEquals(VaultProvisioningPolicy.Decision.BLOCK_NOT_NEW,
                VaultProvisioningPolicy.decideFreshCreation(candidate))
        }
    }

    @Test fun everyInterruptedProvisioningStepBlocksAccess() {
        for (phase in VaultProvisioningPolicy.JournalPhase.entries.filter {
            it != VaultProvisioningPolicy.JournalPhase.COMMITTED
        }) {
            val evidence = empty.copy(knownVault = true, journalPhase = phase,
                inventoryPresent = true, inventoryValid = true,
                epochEnvelopePresent = true, keystoreAliasPresent = true)
            assertEquals(VaultProvisioningPolicy.Decision.BLOCK_INCOMPLETE,
                VaultProvisioningPolicy.decideOpen(evidence))
        }
    }

    @Test fun committedVaultRequiresAllCapabilitiesAndValidInventory() {
        val ready = empty.copy(knownVault = true,
            journalPhase = VaultProvisioningPolicy.JournalPhase.COMMITTED,
            inventoryPresent = true, inventoryValid = true,
            epochEnvelopePresent = true, keystoreAliasPresent = true)
        assertEquals(VaultProvisioningPolicy.Decision.OPEN_EXISTING,
            VaultProvisioningPolicy.decideOpen(ready))
        for (candidate in listOf(
            ready.copy(inventoryPresent = false),
            ready.copy(epochEnvelopePresent = false),
            ready.copy(keystoreAliasPresent = false)
        )) {
            assertEquals(VaultProvisioningPolicy.Decision.BLOCK_MISSING_CAPABILITY,
                VaultProvisioningPolicy.decideOpen(candidate))
        }
        assertEquals(VaultProvisioningPolicy.Decision.BLOCK_CORRUPT,
            VaultProvisioningPolicy.decideOpen(ready.copy(inventoryValid = false)))
        assertEquals(VaultProvisioningPolicy.Decision.BLOCK_CORRUPT,
            VaultProvisioningPolicy.decideOpen(ready.copy(journalCorrupt = true)))
    }
}
