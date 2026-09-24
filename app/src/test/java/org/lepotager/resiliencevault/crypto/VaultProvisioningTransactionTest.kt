package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class VaultProvisioningTransactionTest {
    private val initial = VaultProvisioningJournal("11".repeat(32), "22".repeat(32), 1, VaultProvisioningPolicy.JournalPhase.BEGIN)
    private val committed = initial.copy(phase = VaultProvisioningPolicy.JournalPhase.COMMITTED)
    private class Effects(var failAt: Int = -1) : VaultProvisioningEffects {
        var calls = 0
        var j: VaultJournalRead = VaultJournalRead.Missing
        var r: VaultSecurityRegistryRead = VaultSecurityRegistryRead.Missing
        var blob: ByteArray? = null
        var key: Aead? = null
        private fun boundary() { calls++; if (calls == failAt) throw IOException("interrupted") }
        override fun journal(): VaultJournalRead { boundary(); return j }
        override fun registry(): VaultSecurityRegistryRead { boundary(); return r }
        override fun envelope(): ByteArray? { boundary(); return blob?.copyOf() }
        override fun keyExists(): Boolean { boundary(); return key != null }
        override fun begin(record: VaultProvisioningJournal) { check(j == VaultJournalRead.Missing); j = VaultJournalRead.Ready(record); boundary() }
        override fun advance(previous: VaultProvisioningJournal, next: VaultProvisioningJournal) {
            check(j == VaultJournalRead.Ready(previous)); j = VaultJournalRead.Ready(next); boundary()
        }
        override fun createKey(record: VaultProvisioningJournal) {
            check(key == null); TinkVaultSession.register()
            key = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).aead(); boundary()
        }
        override fun wrapper(record: VaultProvisioningJournal): Aead { boundary(); return checkNotNull(key) }
        override fun writeEnvelope(bytes: ByteArray) { blob = bytes.copyOf(); boundary() }
        override fun publishRegistry(record: VaultSecurityRegistryRecord) { r = VaultSecurityRegistryRead.Ready(record); boundary() }
        override fun alias(record: VaultProvisioningJournal) = "rv.test"
    }
    @Test fun provisioningAndReopenUseSameEpochKey() {
        val effects = Effects()
        val transaction = VaultProvisioningTransaction(effects)
        transaction.createExplicit(initial).use { created ->
            val context = created.context(VaultBinding.Purpose.OBJECT_DATA, "33".repeat(32), 1)
            val encrypted = created.encryptObject(context, byteArrayOf(1, 2, 3))
            transaction.openExisting(committed).use { opened ->
                assertArrayEquals(byteArrayOf(1, 2, 3), opened.decryptObject(context, encrypted))
            }
        }
        assertThrows(Exception::class.java) { transaction.createExplicit(initial) }
    }
    @Test fun interruptionAtEverySideEffectNeverInventsReadyState() {
        val baseline = Effects()
        VaultProvisioningTransaction(baseline).createExplicit(initial).close()
        for (cut in 1..baseline.calls) {
            val effects = Effects(cut)
            assertThrows(Exception::class.java) { VaultProvisioningTransaction(effects).createExplicit(initial) }
            effects.failAt = -1
            val reboot = VaultProvisioningTransaction(effects)
            if (effects.j == VaultJournalRead.Ready(committed)) {
                reboot.openExisting(committed).close()
            } else {
                assertThrows(Exception::class.java) { reboot.openExisting(committed) }
            }
            if (effects.j != VaultJournalRead.Missing) {
                assertThrows(Exception::class.java) { reboot.createExplicit(initial) }
            }
        }
    }
    @Test fun missingKeyCorruptRegistryAndJournalNeverRegenerate() {
        val effects = Effects()
        val transaction = VaultProvisioningTransaction(effects)
        transaction.createExplicit(initial).close()
        effects.key = null
        assertThrows(Exception::class.java) { transaction.openExisting(committed) }
        assertThrows(Exception::class.java) { transaction.createExplicit(initial) }
        effects.r = VaultSecurityRegistryRead.Unavailable
        assertThrows(Exception::class.java) { transaction.openExisting(committed) }
        effects.j = VaultJournalRead.Unavailable
        assertThrows(Exception::class.java) { transaction.createExplicit(initial) }
        assertNull(effects.key)
    }
}
