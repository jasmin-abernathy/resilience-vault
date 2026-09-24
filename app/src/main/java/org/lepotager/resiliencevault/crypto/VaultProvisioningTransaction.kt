package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.Aead

/** Side effects have real Android adapters below; tests inject interruption at each call. */
internal interface VaultProvisioningEffects {
    fun journal(): VaultJournalRead
    fun registry(): VaultSecurityRegistryRead
    fun envelope(): ByteArray?
    fun keyExists(): Boolean
    fun begin(record: VaultProvisioningJournal)
    fun advance(previous: VaultProvisioningJournal, next: VaultProvisioningJournal)
    fun createKey(record: VaultProvisioningJournal)
    fun wrapper(record: VaultProvisioningJournal): Aead
    fun writeEnvelope(bytes: ByteArray)
    fun publishRegistry(record: VaultSecurityRegistryRecord)
    fun alias(record: VaultProvisioningJournal): String
}

/** Must run within the runtime's mutation lease. Interrupted provisioning is blocked, not repaired. */
internal class VaultProvisioningTransaction(private val effects: VaultProvisioningEffects) {
    @Synchronized
    fun createExplicit(record: VaultProvisioningJournal): TinkVaultSession {
        require(record.phase == VaultProvisioningPolicy.JournalPhase.BEGIN)
        check(effects.journal() == VaultJournalRead.Missing)
        check(effects.registry() == VaultSecurityRegistryRead.Missing)
        check(effects.envelope() == null && !effects.keyExists())
        effects.begin(record)
        effects.createKey(record)
        check(effects.keyExists())
        val keyCreated = record.next(VaultProvisioningPolicy.JournalPhase.KEY_CREATED)
        effects.advance(record, keyCreated)
        val session = TinkVaultSession.create(record.vaultIdHex, record.generationHex, record.epoch)
        try {
            val sealed = session.wrapLocal(effects.wrapper(keyCreated))
            effects.writeEnvelope(sealed)
            val readback = checkNotNull(effects.envelope())
            check(readback.contentEquals(sealed))
            TinkVaultSession.openLocal(record.vaultIdHex, record.generationHex, record.epoch,
                readback, effects.wrapper(keyCreated)).close()
            val envelopeWritten = keyCreated.next(VaultProvisioningPolicy.JournalPhase.ENVELOPE_WRITTEN)
            effects.advance(keyCreated, envelopeWritten)
            val registry = VaultSecurityRegistryRecord(record.vaultIdHex, record.generationHex, 1,
                listOf(effects.alias(record)))
            effects.publishRegistry(registry)
            check(effects.registry() == VaultSecurityRegistryRead.Ready(registry) && effects.keyExists())
            val committed = envelopeWritten.next(VaultProvisioningPolicy.JournalPhase.COMMITTED)
            effects.advance(envelopeWritten, committed)
            check(effects.journal() == VaultJournalRead.Ready(committed))
            return session
        } catch (error: Exception) { session.close(); throw error }
    }

    @Synchronized
    fun openExisting(expected: VaultProvisioningJournal): TinkVaultSession {
        require(expected.phase == VaultProvisioningPolicy.JournalPhase.COMMITTED)
        check(effects.journal() == VaultJournalRead.Ready(expected))
        val registry = (effects.registry() as? VaultSecurityRegistryRead.Ready)?.record
            ?: error("Registry unavailable")
        check(registry.vaultIdHex == expected.vaultIdHex && registry.generationHex == expected.generationHex)
        check(registry.aliases == listOf(effects.alias(expected)) && effects.keyExists())
        return TinkVaultSession.openLocal(expected.vaultIdHex, expected.generationHex, expected.epoch,
            checkNotNull(effects.envelope()), effects.wrapper(expected))
    }
}
