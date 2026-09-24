package org.lepotager.resiliencevault.crypto

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.SecureRandom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVaultKekInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun insecureDeviceRefusesKekCreationWithoutFallback() {
        val keyguard = requireNotNull(context.getSystemService(KeyguardManager::class.java))
        assumeFalse("Run this case on a device/emulator without secure lock", keyguard.isDeviceSecure)

        val begin = freshBegin()
        val journal = AtomicVaultProvisioningJournalStore.forVault(context, begin)
        val kek = AndroidVaultKek(context)
        val path = journalPath(begin)
        try {
            journal.create(begin)
            assertThrows(IllegalStateException::class.java) {
                kek.createExplicitly(journal, begin)
            }
            assertFalse(kek.exists(begin))
        } finally {
            runCatching { kek.deleteExisting(begin) }
            deleteJournalFiles(path)
        }
    }

    @Test
    fun secureDeviceCreatesNonExportableKekAndDeletionIsFinal() {
        val keyguard = requireNotNull(context.getSystemService(KeyguardManager::class.java))
        assumeTrue("Requires a secure lock screen on the test device", keyguard.isDeviceSecure)

        val begin = freshBegin()
        val journal = AtomicVaultProvisioningJournalStore.forVault(context, begin)
        val kek = AndroidVaultKek(context)
        val path = journalPath(begin)
        try {
            journal.create(begin)
            val created = kek.createExplicitly(journal, begin)
            assertTrue(kek.exists(begin))
            assertNull("AndroidKeyStore AES key must not be exportable", created.encoded)

            val keyCreated = begin.next(VaultProvisioningPolicy.JournalPhase.KEY_CREATED)
            journal.advance(begin, keyCreated)
            val loaded = kek.loadExisting(journal, keyCreated)
            assertNull("Reloaded AndroidKeyStore key must remain non-exportable", loaded.encoded)

            kek.deleteExisting(keyCreated)
            assertFalse(kek.exists(keyCreated))
            assertThrows(IllegalStateException::class.java) {
                kek.loadExisting(journal, keyCreated)
            }
            assertFalse("A deleted KEK must not be silently regenerated", kek.exists(keyCreated))
        } finally {
            runCatching { kek.deleteExisting(begin) }
            deleteJournalFiles(path)
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

    private fun deleteJournalFiles(path: File) {
        path.delete()
        File(path.path + ".new").delete()
        File(path.path + ".bak").delete()
    }

    private fun randomHex32(): String =
        ByteArray(32)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
