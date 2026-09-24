package org.lepotager.resiliencevault.crypto

import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultSecurityRegistryAdversarialTest {
    private val alias1 = "rv.kek.v1.${"01".repeat(32)}.${"ab".repeat(32)}.e1"
    private val alias2 = "rv.kek.v1.${"01".repeat(32)}.${"ab".repeat(32)}.e2"
    private val initial = VaultSecurityRegistryRecord(
        vaultIdHex = "01".repeat(32),
        generationHex = "ab".repeat(32),
        revision = 1,
        aliases = listOf(alias1),
    )

    @Test
    fun truncatedRegistryLatchesStoreClosedAndCannotBeRecreated() {
        val encoded = VaultSecurityRegistryCodec.encode(initial)
        val file = FakeRegistryFile(encoded.copyOf(encoded.size - 1))
        val store = VerifiedVaultSecurityRegistryStore(file)

        assertEquals(VaultSecurityRegistryRead.Unavailable, store.read())
        assertThrows(IllegalStateException::class.java) { store.createFresh(initial) }
        assertEquals(0, file.writes.get())
    }

    @Test
    fun interruptedRotationWriteIsNotAcknowledgedButRestartSeesPublishedRevision() {
        val file = FakeRegistryFile()
        val store = VerifiedVaultSecurityRegistryStore(file)
        store.createFresh(initial)
        val rotated = initial.copy(
            revision = 2,
            aliases = listOf(alias1, alias2).sorted(),
        )
        file.throwAfterWrite = true

        assertThrows(IOException::class.java) { store.replaceExpected(initial, rotated) }
        assertEquals(VaultSecurityRegistryRead.Unavailable, store.read())

        file.throwAfterWrite = false
        assertEquals(
            VaultSecurityRegistryRead.Ready(rotated),
            VerifiedVaultSecurityRegistryStore(file).read()
        )
    }

    private class FakeRegistryFile(
        initialBytes: ByteArray? = null
    ) : VaultSecurityRegistryFile {
        var bytes: ByteArray? = initialBytes?.clone()
        var throwAfterWrite = false
        val writes = AtomicInteger()

        override fun read(): ByteArray = bytes?.clone() ?: throw FileNotFoundException()

        override fun write(bytes: ByteArray) {
            writes.incrementAndGet()
            this.bytes = bytes.clone()
            if (throwAfterWrite) throw IOException("simulated uncertain registry write")
        }
    }
}
