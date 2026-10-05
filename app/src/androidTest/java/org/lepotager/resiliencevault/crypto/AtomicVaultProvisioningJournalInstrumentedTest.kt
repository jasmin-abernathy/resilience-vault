package org.lepotager.resiliencevault.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicVaultProvisioningJournalInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun realNoBackupAtomicFileRoundTripThenCorruptionFailsClosed() {
        val begin = freshBegin()
        val store = AtomicVaultProvisioningJournalStore.forVault(context, begin)
        val path = journalPath(begin)
        try {
            store.create(begin)
            assertEquals(VaultJournalRead.Ready(begin), store.read())
            assertTrue(
                path.canonicalPath.startsWith(
                    context.noBackupFilesDir.canonicalPath + File.separator
                )
            )

            val keyCreated = begin.next(VaultProvisioningPolicy.JournalPhase.KEY_CREATED)
            store.advance(begin, keyCreated)
            assertEquals(VaultJournalRead.Ready(keyCreated), store.read())

            // Simulate a damaged committed file. A corrupt journal must never be interpreted as
            // a fresh install eligible for silent key regeneration.
            path.writeBytes(byteArrayOf(0x52, 0x56, 0x00))
            assertEquals(VaultJournalRead.Unavailable, store.read())
            assertThrows(IllegalStateException::class.java) {
                store.create(begin)
            }
        } finally {
            path.delete()
            File(path.path + ".new").delete()
            File(path.path + ".bak").delete()
        }
    }

    private fun freshBegin(): VaultProvisioningJournal =
        VaultProvisioningJournal(
            vaultIdHex = randomHex32(),
            generationHex = randomHex32(),
            epoch = 1,
            phase = VaultProvisioningPolicy.JournalPhase.BEGIN,
        )

    private fun journalPath(record: VaultProvisioningJournal): File =
        File(
            context.noBackupFilesDir,
            "security/provisioning/${record.vaultIdHex}.${record.generationHex}.bin"
        )

    private fun randomHex32(): String =
        ByteArray(32)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
