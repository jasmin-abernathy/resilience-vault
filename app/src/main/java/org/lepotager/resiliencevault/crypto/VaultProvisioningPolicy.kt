package org.lepotager.resiliencevault.crypto

/**
 * Decision boundary for the creation of a vault. No read path may call [decideFreshCreation].
 * Durable implementations must write BEGIN before generating an alias and COMMITTED only
 * after the epoch envelope and inventory are durable and verified.
 */
object VaultProvisioningPolicy {
    enum class JournalPhase { BEGIN, KEY_CREATED, ENVELOPE_WRITTEN, COMMITTED }

    data class Evidence(
        val knownVault: Boolean,
        val journalPhase: JournalPhase?,
        val journalCorrupt: Boolean,
        val inventoryPresent: Boolean,
        val inventoryValid: Boolean,
        val epochEnvelopePresent: Boolean,
        val keystoreAliasPresent: Boolean
    )

    enum class Decision {
        CREATE_NEW,
        OPEN_EXISTING,
        BLOCK_INCOMPLETE,
        BLOCK_CORRUPT,
        BLOCK_MISSING_CAPABILITY,
        BLOCK_NOT_NEW
    }

    /** Called only after an explicit user action with a freshly generated vault identity. */
    fun decideFreshCreation(e: Evidence): Decision {
        if (e.journalCorrupt) return Decision.BLOCK_CORRUPT
        if (e.knownVault || e.journalPhase != null || e.inventoryPresent ||
            e.epochEnvelopePresent || e.keystoreAliasPresent) return Decision.BLOCK_NOT_NEW
        return Decision.CREATE_NEW
    }

    /** Never falls back to provisioning, even if every local file is missing. */
    fun decideOpen(e: Evidence): Decision {
        if (e.journalCorrupt || (e.inventoryPresent && !e.inventoryValid)) {
            return Decision.BLOCK_CORRUPT
        }
        if (!e.knownVault) return Decision.BLOCK_NOT_NEW
        if (e.journalPhase != JournalPhase.COMMITTED) return Decision.BLOCK_INCOMPLETE
        if (!e.inventoryPresent || !e.epochEnvelopePresent || !e.keystoreAliasPresent) {
            return Decision.BLOCK_MISSING_CAPABILITY
        }
        return Decision.OPEN_EXISTING
    }
}
