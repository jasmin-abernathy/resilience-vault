package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.PredefinedAeadParameters
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TinkVaultSessionTest {
    private fun session(epoch: Long = 1) = TinkVaultSession.create("11".repeat(32), "22".repeat(32), epoch)
    @Test fun objectRoundTripAndContextIsolation() {
        session().use { vault ->
            val binding = vault.context(VaultBinding.Purpose.OBJECT_DATA, "33".repeat(32), 1)
            for (length in listOf(0, 1, 1048575, 1048576, 1048577)) {
                val clear = ByteArray(length) { (it % 251).toByte() }
                val cipher = vault.encryptObject(binding, clear)
                assertArrayEquals(clear, vault.decryptObject(binding, cipher))
                for (wrong in listOf(binding.copy(vaultIdHex = "44".repeat(32)),
                    binding.copy(generationHex = "44".repeat(32)), binding.copy(objectIdHex = "44".repeat(32)),
                    binding.copy(keyEpoch = 2), binding.copy(revision = 2))) {
                    assertThrows(Exception::class.java) { vault.decryptObject(wrong, cipher) }
                }
                for (bad in listOf(cipher.copyOf(cipher.size - 1), cipher + byteArrayOf(0),
                    cipher.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })) {
                    assertThrows(Exception::class.java) { vault.decryptObject(binding, bad) }
                }
            }
        }
    }
    @Test fun localEnvelopeRequiresKekAndExpectedEpoch() = runTest {
        session().use { vault ->
            val kek = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).aead()
            val localKek = TestLocalKekEnvelope(kek)
            val wrapped = vault.wrapLocal(localKek)
            TinkVaultSession.openLocal(vault.vaultId, vault.generation, 1, wrapped, localKek).use { reopened ->
                val context = vault.context(VaultBinding.Purpose.MANIFEST, "44".repeat(32), 1)
                val sealed = vault.sealManifest(context, byteArrayOf(1, 2, 3))
                assertArrayEquals(byteArrayOf(1, 2, 3), reopened.openManifest(context, sealed))
            }
            assertSuspendFails {
                TinkVaultSession.openLocal(vault.vaultId, vault.generation, 2, wrapped, localKek)
            }
            val wrong = TestLocalKekEnvelope(
                KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).aead()
            )
            assertSuspendFails {
                TinkVaultSession.openLocal(vault.vaultId, vault.generation, 1, wrapped, wrong)
            }
            val corrupt = wrapped.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertSuspendFails {
                TinkVaultSession.openLocal(vault.vaultId, vault.generation, 1, corrupt, localKek)
            }
        }
    }
    @Test fun invalidatedHandleCannotReopenOrGenerateObjects() {
        val vault = session()
        val context = vault.context(VaultBinding.Purpose.OBJECT_DATA, "33".repeat(32), 1)
        val ciphertext = vault.encryptObject(context, byteArrayOf(1))
        vault.close(); vault.close()
        assertThrows(Exception::class.java) { vault.decryptObject(context, ciphertext) }
        assertThrows(Exception::class.java) { vault.encryptObject(context, byteArrayOf(1)) }
    }
    @Test fun versionAndLengthDowngradesFail() {
        session().use { vault ->
            val context = vault.context(VaultBinding.Purpose.OBJECT_DATA, "33".repeat(32), 1)
            val cipher = vault.encryptObject(context, byteArrayOf())
            for (offset in listOf(0, 5, 6, 7, 120, 121, 122)) {
                val bad = cipher.copyOf().also { it[offset] = 127 }
                assertThrows(Exception::class.java) { vault.decryptObject(context, bad) }
            }
        }
    }
}
