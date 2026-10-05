package org.lepotager.resiliencevault.crypto

import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VerifiedVaultProvisioningJournalStoreTest {
    private val begin = VaultProvisioningJournal(
        "01".repeat(32), "ab".repeat(32), 1,
        VaultProvisioningPolicy.JournalPhase.BEGIN
    )

    @Test
    fun explicit_create_and_single_step_advance_round_trip() {
        val file = FakeJournalFile()
        val store = VerifiedVaultProvisioningJournalStore(file)
        assertEquals(VaultJournalRead.Missing, store.read())

        store.create(begin)
        assertEquals(VaultJournalRead.Ready(begin), store.read())

        val keyCreated = begin.next(VaultProvisioningPolicy.JournalPhase.KEY_CREATED)
        store.advance(begin, keyCreated)
        assertEquals(VaultJournalRead.Ready(keyCreated), store.read())
        assertEquals(2, file.writes.get())
    }

    @Test
    fun silent_write_failure_latches_store_closed() {
        val file = FakeJournalFile().apply { ignoreWrites = true }
        val store = VerifiedVaultProvisioningJournalStore(file)

        assertThrows(IllegalStateException::class.java) { store.create(begin) }
        assertEquals(VaultJournalRead.Unavailable, store.read())
        assertEquals(1, file.writes.get())
    }

    @Test
    fun failure_after_publish_is_not_acknowledged_but_restart_can_observe_bytes() {
        val file = FakeJournalFile().apply { throwAfterWrite = true }
        val first = VerifiedVaultProvisioningJournalStore(file)

        assertThrows(IOException::class.java) { first.create(begin) }
        assertEquals(VaultJournalRead.Unavailable, first.read())

        file.throwAfterWrite = false
        val restarted = VerifiedVaultProvisioningJournalStore(file)
        assertEquals(VaultJournalRead.Ready(begin), restarted.read())
    }

    @Test
    fun corrupt_record_never_becomes_a_fresh_journal() {
        val file = FakeJournalFile().apply { bytes = byteArrayOf(1, 2, 3) }
        val store = VerifiedVaultProvisioningJournalStore(file)

        assertEquals(VaultJournalRead.Unavailable, store.read())
        assertThrows(IllegalStateException::class.java) { store.create(begin) }
        assertEquals(0, file.writes.get())
    }

    @Test
    fun concurrent_explicit_creation_has_one_committed_winner() {
        val file = FakeJournalFile()
        val store = VerifiedVaultProvisioningJournalStore(file)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val failures = AtomicInteger()
        val workers = List(12) {
            thread(start = false) {
                start.await()
                try {
                    store.create(begin)
                    successes.incrementAndGet()
                } catch (_: Exception) {
                    failures.incrementAndGet()
                }
            }
        }

        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(1, successes.get())
        assertEquals(11, failures.get())
        assertEquals(1, file.writes.get())
        assertEquals(VaultJournalRead.Ready(begin), store.read())
    }

    @Test
    fun skipped_or_foreign_transition_is_rejected_without_write() {
        val file = FakeJournalFile().apply {
            bytes = VaultProvisioningJournalCodec.encode(begin)
        }
        val store = VerifiedVaultProvisioningJournalStore(file)
        val skipped = begin.copy(phase = VaultProvisioningPolicy.JournalPhase.COMMITTED)
        val foreign = begin.next(VaultProvisioningPolicy.JournalPhase.KEY_CREATED).copy(
            generationHex = "cd".repeat(32)
        )

        assertThrows(IllegalArgumentException::class.java) { store.advance(begin, skipped) }
        assertThrows(IllegalArgumentException::class.java) { store.advance(begin, foreign) }
        assertTrue(file.writes.get() == 0)
    }

    private class FakeJournalFile : VaultProvisioningJournalFile {
        @Volatile var bytes: ByteArray? = null
        @Volatile var ignoreWrites = false
        @Volatile var throwAfterWrite = false
        val writes = AtomicInteger()

        override fun read(): ByteArray =
            bytes?.clone() ?: throw FileNotFoundException()

        override fun write(bytes: ByteArray) {
            writes.incrementAndGet()
            if (!ignoreWrites) this.bytes = bytes.clone()
            if (throwAfterWrite) throw IOException("simulated sync failure")
        }
    }
}
