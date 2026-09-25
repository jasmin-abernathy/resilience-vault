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
    fun explicit_first_install_initializes_only_missing_state_and_never_resets_corruption() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        val missing = FakeStateFile().apply { bytes = null }
        val fresh = VerifiedPanicStateStore(missing, dispatcher)
        assertTrue(fresh.read() is PanicStoreReadResult.Unavailable)
        assertTrue(fresh.initializeFresh() is PanicInitializationResult.Created)
        assertEquals(PanicPersistentState.initial(), (fresh.read() as PanicStoreReadResult.Ready).state)
        assertTrue(fresh.initializeFresh() is PanicInitializationResult.AlreadyInitialized)
        assertEquals(1, missing.writes)

        val corrupt = FakeStateFile().apply { bytes = byteArrayOf(1, 2, 3) }
        val broken = VerifiedPanicStateStore(corrupt, dispatcher)
        assertTrue(broken.initializeFresh() is PanicInitializationResult.Unavailable)
        assertEquals(0, corrupt.writes)

        val pending = PanicPersistentState.localPendingWithoutRemoteProof("e".repeat(64))
        val existing = FakeStateFile().apply { bytes = PanicStateCodec.encode(pending) }
        val existingStore = VerifiedPanicStateStore(existing, dispatcher)
        assertTrue(existingStore.initializeFresh() is PanicInitializationResult.AlreadyInitialized)
        assertEquals(pending, (existingStore.read() as PanicStoreReadResult.Ready).state)
        assertEquals(0, existing.writes)
    }

    @Test
    fun failed_first_install_commit_latches_closed() = runTest {
        val file = FakeStateFile().apply { bytes = null; ignoreWrites = true }
        val store = VerifiedPanicStateStore(file, StandardTestDispatcher(testScheduler))
        assertTrue(store.initializeFresh() is PanicInitializationResult.Unavailable)
        file.ignoreWrites = false
        assertTrue(store.initializeFresh() is PanicInitializationResult.Unavailable)
        assertTrue(store.read() is PanicStoreReadResult.Unavailable)
    }

    @Test
    fun reading_v1_never_rewrites_it_and_a_later_locked_mutation_commits_v2() = runTest {
        val legacy = LegacyPanicStateV1Fixture.encode("IDLE")
        val file = FakeStateFile().apply {
            bytes = legacy
            writes = 0
        }
        val store = VerifiedPanicStateStore(file, StandardTestDispatcher(testScheduler))

        val read = store.read() as PanicStoreReadResult.Ready
        assertEquals(RemoteDeleteConfiguration.UNKNOWN, read.state.remoteDeleteConfiguration)
        assertEquals(0, file.writes)
        assertEquals(1, LegacyPanicStateV1Fixture.version(checkNotNull(file.bytes)))

        val result = store.transaction { current ->
            PanicStateMutation.Replace(
                current.copy(remoteDeleteConfiguration = RemoteDeleteConfiguration.NOT_CONFIGURED),
                true,
            )
        }
        assertTrue(result is PanicTransactionResult.Success)
        assertEquals(1, file.writes)
        assertEquals(2, LegacyPanicStateV1Fixture.version(checkNotNull(file.bytes)))
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
        val pending = PanicPersistentState(
            phase = PanicPhase.LOCAL_PENDING,
            panicIdHex = "c".repeat(64),
            remoteDeleteConfiguration = RemoteDeleteConfiguration.NOT_CONFIGURED,
            remoteDeleteCheckpoint = RemoteDeleteCheckpoint.NOT_CONFIGURED,
        )
        val post = pending.copy(phase = PanicPhase.POST_PENDING, purgeComplete = true)
        val complete = post.copy(phase = PanicPhase.COMPLETE, sessionRevocationComplete = true)
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
