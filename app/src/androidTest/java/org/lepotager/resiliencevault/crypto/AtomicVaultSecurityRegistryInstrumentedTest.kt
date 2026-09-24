package org.lepotager.resiliencevault.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicVaultSecurityRegistryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val path: File
        get() = File(
            context.noBackupFilesDir,
            "security/vault-security-registry.bin"
        )

    @Test
    fun realAtomicFilePersistsCanonicalTwoAliasRotationThenFailsClosedOnCorruption() {
        cleanup()
        val vault = randomHex32()
        val generation = randomHex32()
        val alias1 = "rv.kek.v1.$vault.$generation.e1"
        val alias2 = "rv.kek.v1.$vault.$generation.e2"
        val initial = VaultSecurityRegistryRecord(
            vaultIdHex = vault,
            generationHex = generation,
            revision = 1,
            aliases = listOf(alias1),
        )
        val rotated = initial.copy(
            revision = 2,
            aliases = listOf(alias1, alias2).sorted(),
        )

        try {
            val store = AtomicVaultSecurityRegistryStore.create(context)
            assertEquals(VaultSecurityRegistryRead.Missing, store.read())

            store.createFresh(initial)
            assertEquals(VaultSecurityRegistryRead.Ready(initial), store.read())
            assertTrue(
                path.canonicalPath.startsWith(
                    context.noBackupFilesDir.canonicalPath + File.separator
                )
            )

            store.replaceExpected(initial, rotated)
            assertEquals(VaultSecurityRegistryRead.Ready(rotated), store.read())

            // Corruption must latch this process-local store closed. It must never become Missing.
            path.writeBytes(byteArrayOf(0x52, 0x56, 0x00))
            assertEquals(VaultSecurityRegistryRead.Unavailable, store.read())
            assertThrows(IllegalStateException::class.java) {
                store.createFresh(initial)
            }
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        path.delete()
        File(path.path + ".new").delete()
        File(path.path + ".bak").delete()
    }

    private fun randomHex32(): String =
        ByteArray(32)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
