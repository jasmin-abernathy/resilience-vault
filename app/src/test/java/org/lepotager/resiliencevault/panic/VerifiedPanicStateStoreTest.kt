package org.lepotager.resiliencevault.panic

import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VerifiedPanicStateStoreTest {
    @Test
    fun silent_write_failure_never_reports_acceptance_and_latches_gate_closed() = runTest {
        val file = FakeStateFile()
        val store = VerifiedPanicStateStore(file, StandardTestDispatcher(testScheduler))
        file.ignoreWrites = true
        val admission = PanicAdmissionService(store)
        assertTrue(admission.acceptLocal() is AdmissionResult.StorageUnavailable)
        assertTrue(PanicAccessGate(store).check() is VaultAccessDecision.Blocked)
        file.ignoreWrites = false
        assertTrue(store.read() is PanicStoreReadResult.Unavailable)
    }

    @Test
    fun failed_write_after_publish_is_not_acknowledged_and_restart_recovers_intention() = runTest {
        val file = FakeStateFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = VerifiedPanicStateStore(file, dispatcher)
        file.throwAfterWrite = true
        assertTrue(PanicAdmissionService(first).acceptLocal() is AdmissionResult.StorageUnavailable)
        assertTrue(PanicAccessGate(first).check() is VaultAccessDecision.Blocked)
        file.throwAfterWrite = false
        val restarted = VerifiedPanicStateStore(file, dispatcher)
        assertEquals(PanicPhase.LOCAL_PENDING, (restarted.read() as PanicStoreReadResult.Ready).state.phase)
        assertTrue(PanicAccessGate(restarted).check() is VaultAccessDecision.Blocked)
    }

    @Test
    fun missing_and_corrupt_records_are_never_reinitialized() = runTest {
        for (bytes in listOf(null, byteArrayOf(1, 2), ByteArray(PanicStateCodec.MAX_FILE_BYTES + 1))) {
            val file = FakeStateFile().apply { this.bytes = bytes }
            val store = VerifiedPanicStateStore(file, StandardTestDispatcher(testScheduler))
            assertTrue(PanicAdmissionService(store).acceptLocal() is AdmissionResult.StorageUnavailable)
            assertTrue(PanicAccessGate(store).check() is VaultAccessDecision.Blocked)
            assertEquals(0, file.writes)
        }
    }

    @Test
    fun real_threads_sharing_store_have_one_committed_winner() = runTest {
        val file = FakeStateFile()
        val store = VerifiedPanicStateStore(file)
        val services = List(16) { PanicAdmissionService(store) }
        val results = services.map { service -> async(Dispatchers.Default) { service.acceptLocal() } }.awaitAll()
        assertEquals(1, results.count { it is AdmissionResult.Accepted })
        assertEquals(1, file.writes)
    }

    @Test
    fun legal_states_cannot_be_used_to_reset_or_skip_panic() = runTest {
        val pending = PanicPersistentState(phase = PanicPhase.LOCAL_PENDING, panicIdHex = "c".repeat(64))
        val post = pending.copy(phase = PanicPhase.POST_PENDING, purgeComplete = true)
        val complete = post.copy(phase = PanicPhase.COMPLETE, sessionRevocationComplete = true, remoteDeleteComplete = true)
        val invalidTransitions = listOf(
            pending to PanicPersistentState.initial(),
            PanicPersistentState.initial() to post,
            post to post.copy(panicIdHex = "d".repeat(64)),
            post to post.copy(purgeComplete = false),
            complete to post
        )
        for ((before, after) in invalidTransitions) {
            val file = FakeStateFile().apply { bytes = PanicStateCodec.encode(before) }
            val store = VerifiedPanicStateStore(file, StandardTestDispatcher(testScheduler))
            assertTrue(store.transaction { PanicStateMutation.Replace(after, true) } is PanicTransactionResult.Unavailable)
            assertEquals(0, file.writes)
        }
    }

    private class FakeStateFile : PanicStateFile {
        var bytes: ByteArray? = PanicStateCodec.encode(PanicPersistentState.initial())
        var ignoreWrites = false
        var throwAfterWrite = false
        var writes = 0
        override fun read(): ByteArray = bytes?.clone() ?: throw FileNotFoundException()
        override fun write(bytes: ByteArray) {
            writes++
            if (!ignoreWrites) this.bytes = bytes.clone()
            if (throwAfterWrite) throw IOException("simulated sync failure")
        }
    }
}
