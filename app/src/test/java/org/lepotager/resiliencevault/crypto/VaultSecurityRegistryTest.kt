package org.lepotager.resiliencevault.crypto

import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultSecurityRegistryTest {
    private val alias1 = "rv.kek.v1.${"01".repeat(32)}.${"ab".repeat(32)}.e1"
    private val alias2 = "rv.kek.v1.${"01".repeat(32)}.${"ab".repeat(32)}.e2"
    private val initial = VaultSecurityRegistryRecord(
        vaultIdHex = "01".repeat(32),
        generationHex = "ab".repeat(32),
        revision = 1,
        aliases = listOf(alias1),
    )

    @Test
    fun codec_round_trip_preserves_multiple_rotation_aliases() {
        val rotating = initial.copy(
            revision = 2,
            aliases = listOf(alias1, alias2).sorted(),
        )
        val encoded = VaultSecurityRegistryCodec.encode(rotating)
        assertEquals(rotating, VaultSecurityRegistryCodec.decode(encoded))
    }

    @Test
    fun duplicate_unsorted_and_invalid_aliases_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(aliases = listOf(alias1, alias1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(aliases = listOf(alias2, alias1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            initial.copy(aliases = listOf("not-a-vault-alias"))
        }
    }

    @Test
    fun tampering_truncation_and_unknown_version_fail_closed() {
        val encoded = VaultSecurityRegistryCodec.encode(initial)
        for (candidate in listOf(
            encoded.copyOf(encoded.size - 1),
            encoded.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() },
            encoded.copyOf().also { it[0] = 0 },
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                VaultSecurityRegistryCodec.decode(candidate)
            }
        }
    }

    @Test
    fun verified_store_requires_monotonic_same_generation_replacement() {
        val file = FakeRegistryFile()
        val store = VerifiedVaultSecurityRegistryStore(file)
        store.createFresh(initial)
        val rotating = initial.copy(
            revision = 2,
            aliases = listOf(alias1, alias2).sorted(),
        )
        store.replaceExpected(initial, rotating)
        assertEquals(VaultSecurityRegistryRead.Ready(rotating), store.read())

        assertThrows(IllegalArgumentException::class.java) {
            store.replaceExpected(rotating, rotating.copy(
                revision = 3,
                generationHex = "cd".repeat(32),
            ))
        }
    }

    @Test
    fun uncertain_write_latches_registry_closed() {
        val file = FakeRegistryFile().apply { ignoreWrites = true }
        val store = VerifiedVaultSecurityRegistryStore(file)
        assertThrows(IllegalStateException::class.java) { store.createFresh(initial) }
        assertEquals(VaultSecurityRegistryRead.Unavailable, store.read())
    }

    @Test
    fun published_then_failed_write_is_not_acknowledged() {
        val file = FakeRegistryFile().apply { throwAfterWrite = true }
        val store = VerifiedVaultSecurityRegistryStore(file)
        assertThrows(IOException::class.java) { store.createFresh(initial) }
        assertEquals(VaultSecurityRegistryRead.Unavailable, store.read())

        file.throwAfterWrite = false
        assertEquals(
            VaultSecurityRegistryRead.Ready(initial),
            VerifiedVaultSecurityRegistryStore(file).read()
        )
    }

    private class FakeRegistryFile : VaultSecurityRegistryFile {
        var bytes: ByteArray? = null
        var ignoreWrites = false
        var throwAfterWrite = false
        val writes = AtomicInteger()

        override fun read(): ByteArray = bytes?.clone() ?: throw FileNotFoundException()

        override fun write(bytes: ByteArray) {
            writes.incrementAndGet()
            if (!ignoreWrites) this.bytes = bytes.clone()
            if (throwAfterWrite) throw IOException("simulated sync failure")
        }
    }
}
