package org.lepotager.resiliencevault.crypto

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface VaultProvisioningEffects {
    fun journal(): VaultJournalRead
    fun registry(): VaultSecurityRegistryRead
    fun envelope(): ByteArray?
    fun keyExists(): Boolean
    fun begin(record: VaultProvisioningJournal)
    fun advance(previous: VaultProvisioningJournal, next: VaultProvisioningJournal)
    fun createKey(record: VaultProvisioningJournal)
    fun localKek(record: VaultProvisioningJournal): LocalKekEnvelope
    fun writeEnvelope(bytes: ByteArray)
    fun publishRegistry(record: VaultSecurityRegistryRecord)
    fun alias(record: VaultProvisioningJournal): String
}

/** Must run within the runtime mutation lease. Interrupted provisioning is blocked, not repaired. */
internal class VaultProvisioningTransaction(private val effects: VaultProvisioningEffects) {
    private val mutex = Mutex()

    suspend fun createExplicit(record: VaultProvisioningJournal): TinkVaultSession = mutex.withLock {
        require(record.phase == VaultProvisioningPolicy.JournalPhase.BEGIN)
        check(effects.journal() == VaultJournalRead.Missing)
        check(effects.registry() == VaultSecurityRegistryRead.Missing)
        check(effects.envelope() == null && !effects.keyExists())

        active()
        effects.begin(record)
        effects.createKey(record)
        check(effects.keyExists())

        val keyCreated = record.next(VaultProvisioningPolicy.JournalPhase.KEY_CREATED)
        active()
        effects.advance(record, keyCreated)

        val session = TinkVaultSession.create(record.vaultIdHex, record.generationHex, record.epoch)
        try {
            val sealed = session.wrapLocal(effects.localKek(keyCreated))
            active()
            effects.writeEnvelope(sealed)

            val readback = checkNotNull(effects.envelope())
            check(readback.contentEquals(sealed))
            TinkVaultSession.openLocal(
                record.vaultIdHex,
                record.generationHex,
                record.epoch,
                readback,
                effects.localKek(keyCreated),
            ).close()

            val envelopeWritten = keyCreated.next(VaultProvisioningPolicy.JournalPhase.ENVELOPE_WRITTEN)
            active()
            effects.advance(keyCreated, envelopeWritten)

            val registry = VaultSecurityRegistryRecord(
                record.vaultIdHex,
                record.generationHex,
                1,
                listOf(effects.alias(record)),
            )
            active()
            effects.publishRegistry(registry)
            check(effects.registry() == VaultSecurityRegistryRead.Ready(registry) && effects.keyExists())

            val committed = envelopeWritten.next(VaultProvisioningPolicy.JournalPhase.COMMITTED)
            active()
            effects.advance(envelopeWritten, committed)
            check(effects.journal() == VaultJournalRead.Ready(committed))
            session
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    suspend fun openExisting(expected: VaultProvisioningJournal): TinkVaultSession = mutex.withLock {
        require(expected.phase == VaultProvisioningPolicy.JournalPhase.COMMITTED)
        check(effects.journal() == VaultJournalRead.Ready(expected))
        val registry = (effects.registry() as? VaultSecurityRegistryRead.Ready)?.record
            ?: error("Registry unavailable")
        check(registry.vaultIdHex == expected.vaultIdHex && registry.generationHex == expected.generationHex)
        check(registry.aliases == listOf(effects.alias(expected)) && effects.keyExists())
        active()
        TinkVaultSession.openLocal(
            expected.vaultIdHex,
            expected.generationHex,
            expected.epoch,
            checkNotNull(effects.envelope()),
            effects.localKek(expected),
        )
    }

    private suspend fun active() {
        currentCoroutineContext().ensureActive()
    }
}
