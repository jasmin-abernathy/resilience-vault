package org.lepotager.resiliencevault.crypto

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultProvisioningJournalInterruptionTest {
    private val begin = VaultProvisioningJournal(
        vaultIdHex = "01".repeat(32),
        generationHex = "ab".repeat(32),
        epoch = 1,
        phase = VaultProvisioningPolicy.JournalPhase.BEGIN,
    )

    @Test
    fun interruptedAdvanceIsNeverAcknowledgedByOriginalStore() {
        val file = FakeJournalFile()
        val store = VerifiedVaultProvisioningJournalStore(file)
        store.create(begin)
        val next = begin.next(VaultProvisioningPolicy.JournalPhase.KEY_CREATED)
        file.throwAfterWrite = true

        assertThrows(IOException::class.java) { store.advance(begin, next) }
        assertEquals(VaultJournalRead.Unavailable, store.read())

        file.throwAfterWrite = false
        assertEquals(
            VaultJournalRead.Ready(next),
            VerifiedVaultProvisioningJournalStore(file).read()
        )
    }

    private class FakeJournalFile : VaultProvisioningJournalFile {
        var bytes: ByteArray? = null
        var throwAfterWrite = false

        override fun read(): ByteArray = bytes?.clone() ?: throw FileNotFoundException()

        override fun write(bytes: ByteArray) {
            this.bytes = bytes.clone()
            if (throwAfterWrite) throw IOException("simulated uncertain journal advance")
        }
    }
}
