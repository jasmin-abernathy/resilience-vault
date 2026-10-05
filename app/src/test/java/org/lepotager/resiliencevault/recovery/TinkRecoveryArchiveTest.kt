package org.lepotager.resiliencevault.recovery

import org.lepotager.resiliencevault.crypto.TinkVaultSession
import org.lepotager.resiliencevault.crypto.VaultBinding
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class TinkRecoveryArchiveTest {
    private fun source() = TinkVaultSession.create("11".repeat(32), "22".repeat(32), 1)
    private fun export(vault: TinkVaultSession): TinkRecoveryArchive.Export {
        val context = vault.context(VaultBinding.Purpose.OBJECT_DATA, "33".repeat(32), 1)
        val plain = "recovery fixture".toByteArray()
        return TinkRecoveryArchive.prepare(vault, "44".repeat(32), 1,
            listOf(TinkRecoveryArchive.ObjectRecord(context, plain.size, vault.encryptObject(context, plain))))
    }
    private fun verify(export: TinkRecoveryArchive.Export, kit: ByteArray = export.kit, archive: ByteArray = export.archive,
                       binding: RecoveryArtifactBinding = export.binding) =
        TinkRecoveryArchive.verify({ ByteArrayInputStream(kit) }, { ByteArrayInputStream(archive) }, binding,
            RecoveryArchiveDeleteScope.OUTSIDE_ACTIVE_DELETE_SCOPE)

    @Test fun sourceDestroyedThenRecoveryReencryptsForDifferentDevice() {
        val a = source(); val saved = export(a); a.close()
        verify(saved).use { verified ->
            assertEquals(1, verified.trial().restoredObjectCount.toInt())
            TinkVaultSession.create("55".repeat(32), "66".repeat(32), 1).use { b ->
                val recovered = verified.recoverIntoFreshSession(b)
                val objectB = recovered.single()
                assertArrayEquals("recovery fixture".toByteArray(), b.decryptObject(objectB.binding, objectB.ciphertext))
                assertEquals(b.vaultId, objectB.binding.vaultIdHex)
                assertEquals(b.generation, objectB.binding.generationHex)
            }
            source().use { reused -> assertThrows(Exception::class.java) { verified.recoverIntoFreshSession(reused) } }
        }
    }
    @Test fun missingWrongTruncatedAndStaleArtifactsFail() {
        source().use { a ->
            val saved = export(a)
            val other = export(a)
            for (kit in listOf(byteArrayOf(), other.kit, saved.kit.copyOf(saved.kit.size - 1),
                saved.kit.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })) {
                assertThrows(Exception::class.java) { verify(saved, kit = kit).close() }
            }
            for (archive in listOf(byteArrayOf(), saved.archive.copyOf(saved.archive.size - 1),
                saved.archive + byteArrayOf(0), saved.archive.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })) {
                assertThrows(Exception::class.java) { verify(saved, archive = archive).close() }
            }
            for (binding in listOf(saved.binding.copy(headHex = "77".repeat(32)),
                saved.binding.copy(generationHex = "77".repeat(32)), saved.binding.copy(vaultIdHex = "77".repeat(32)))) {
                assertThrows(Exception::class.java) { verify(saved, binding = binding).close() }
            }
        }
    }
    @Test fun allInputsCloseBeforeEvidenceAndCloseErrorsReject() {
        source().use { a ->
            val saved = export(a)
            var closed = 0
            fun input(bytes: ByteArray) = object : ByteArrayInputStream(bytes) {
                override fun close() { closed++; super.close() }
            }
            TinkRecoveryArchive.verify({ input(saved.kit) }, { input(saved.archive) }, saved.binding,
                RecoveryArchiveDeleteScope.INSIDE_OR_UNKNOWN).use { verified ->
                assertEquals(2, closed)
                assertEquals(RecoveryArchiveDeleteScope.INSIDE_OR_UNKNOWN, verified.archiveEvidence.deleteScope)
            }
            assertThrows(Exception::class.java) {
                TinkRecoveryArchive.verify({ object : ByteArrayInputStream(saved.kit) {
                    override fun close() { throw java.io.IOException("close failed") }
                } }, { input(saved.archive) }, saved.binding, RecoveryArchiveDeleteScope.OUTSIDE_ACTIVE_DELETE_SCOPE)
            }
        }
    }
}
