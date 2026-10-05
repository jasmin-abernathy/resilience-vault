package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VaultKekRotationTest {
    private val vault = "11".repeat(32)
    private val generation = "22".repeat(32)
    private val original = "rv.kek.v1.$vault.$generation.e1"
    private inner class Effects : VaultKekRotationEffects {
        var calls = 0
        var failAt = -1
        var record: VaultKekRotationRecord? = null
        var reg = VaultSecurityRegistryRecord(vault, generation, 1, listOf(original))
        val keys = mutableMapOf(original to newKey())
        var corruptEnvelope = false
        var oldDeleted = false
        private fun boundary() { calls++; if (calls == failAt) throw IOException("interrupted") }
        override fun state(): VaultKekRotationRecord? { boundary(); return record }
        override fun writeState(expected: VaultKekRotationRecord?, next: VaultKekRotationRecord) {
            check(record == expected)
            record = VaultKekRotationCodec.decode(VaultKekRotationCodec.encode(next))
            if (corruptEnvelope && next.phase == VaultKekRotationRecord.Phase.VERIFIED) {
                val broken = next.envelope.toByteArray(); broken[broken.lastIndex] = (broken.last().toInt() xor 1).toByte()
                record = next.copy(envelope = broken.toList())
            }
            boundary()
        }
        override fun registry(): VaultSecurityRegistryRecord { boundary(); return reg }
        override fun replaceRegistry(expected: VaultSecurityRegistryRecord, next: VaultSecurityRegistryRecord) {
            check(reg == expected); reg = next; boundary()
        }
        override fun exists(alias: String): Boolean { boundary(); return alias in keys }
        override fun createKey(record: VaultKekRotationRecord) {
            check(record.newAlias !in keys); keys[record.newAlias] = newKey(); boundary()
        }
        val observedAad = mutableListOf<Pair<String, ByteArray>>()
        override fun localKek(alias: String): LocalKekEnvelope {
            boundary()
            val delegate = TestLocalKekEnvelope(checkNotNull(keys[alias]))
            return object : LocalKekEnvelope {
                override suspend fun encryptKeyset(handle: KeysetHandle, aad: ByteArray): ByteArray {
                    observedAad += alias to aad.copyOf()
                    return delegate.encryptKeyset(handle, aad)
                }
                override suspend fun decryptKeyset(envelope: ByteArray, aad: ByteArray): KeysetHandle {
                    observedAad += alias to aad.copyOf()
                    return delegate.decryptKeyset(envelope, aad)
                }
            }
        }
        override fun deleteKey(alias: String) {
            check(record?.phase == VaultKekRotationRecord.Phase.VERIFIED)
            check(keys.containsKey(checkNotNull(record).newAlias))
            keys.remove(alias); if (alias == original) oldDeleted = true; boundary()
        }
    }
    private fun newKey(): Aead {
        TinkVaultSession.register()
        return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).aead()
    }
    @Test fun repeatRotationPreservesObjectsAndDeletesOnlyPreviousAlias() = runTest {
        val effects = Effects()
        TinkVaultSession.create(vault, generation, 1).use { session ->
            val binding = session.context(VaultBinding.Purpose.OBJECT_DATA, "33".repeat(32), 1)
            val ciphertext = session.encryptObject(binding, byteArrayOf(1, 2, 3))
            repeat(3) {
                VaultKekRotation(effects).rotate(session)
                VaultKekRotation(effects).openExisting(vault, generation, 1).use { opened ->
                    assertArrayEquals(byteArrayOf(1, 2, 3), opened.decryptObject(binding, ciphertext))
                }
                assertEquals(setOf(effects.reg.aliases.single()), effects.keys.keys)
                assertTrue(effects.observedAad.isNotEmpty())
                effects.observedAad.forEach { (alias, aad) ->
                    val suffix = alias.toByteArray(Charsets.US_ASCII)
                    assertTrue(aad.size >= suffix.size)
                    assertArrayEquals(suffix, aad.copyOfRange(aad.size - suffix.size, aad.size))
                }
            }
        }
    }
    @Test fun everyInterruptionBlocksIncompleteRotationAndNeverLosesBothKeys() = runTest {
        val baseline = Effects()
        TinkVaultSession.create(vault, generation, 1).use { VaultKekRotation(baseline).rotate(it) }
        for (cut in 1..baseline.calls) {
            val effects = Effects().apply { failAt = cut }
            TinkVaultSession.create(vault, generation, 1).use { session ->
                assertSuspendFails { VaultKekRotation(effects).rotate(session) }
            }
            effects.failAt = -1
            assertTrue(effects.keys.isNotEmpty())
            val state = effects.record
            if (state?.phase == VaultKekRotationRecord.Phase.COMMITTED) {
                VaultKekRotation(effects).openExisting(vault, generation, 1).close()
            } else {
                assertSuspendFails { VaultKekRotation(effects).openExisting(vault, generation, 1) }
            }
            if (effects.oldDeleted) {
                assertNotNull(state)
                assertTrue(checkNotNull(state).envelope.isNotEmpty())
                assertTrue(effects.keys.containsKey(state.newAlias))
            }
        }
    }
    @Test fun corruptReadbackWrongIdentityAndMissingNewKeyFailClosed() = runTest {
        val broken = Effects().apply { corruptEnvelope = true }
        TinkVaultSession.create(vault, generation, 1).use { session ->
            assertSuspendFails { VaultKekRotation(broken).rotate(session) }
        }
        assertFalse(broken.oldDeleted)
        val effects = Effects()
        TinkVaultSession.create(vault, generation, 1).use { VaultKekRotation(effects).rotate(it) }
        assertSuspendFails { VaultKekRotation(effects).openExisting("44".repeat(32), generation, 1) }
        val bytes = VaultKekRotationCodec.encode(checkNotNull(effects.record))
        assertThrows(Exception::class.java) { VaultKekRotationCodec.decode(bytes + 0) }
        bytes[0] = 0
        assertThrows(Exception::class.java) { VaultKekRotationCodec.decode(bytes) }
        effects.keys.clear()
        assertSuspendFails { VaultKekRotation(effects).openExisting(vault, generation, 1) }
    }
}
