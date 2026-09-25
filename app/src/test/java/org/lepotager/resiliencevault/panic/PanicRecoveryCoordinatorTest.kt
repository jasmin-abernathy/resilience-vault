package org.lepotager.resiliencevault.panic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanicRecoveryCoordinatorTest {
    @Test
    fun key_failure_never_enters_post_phase_or_calls_network() = runTest {
        val store = pendingStore()
        val local = RecordingLocalEffects(
            invalidate = PanicEffectResult.COMPLETED,
            keys = PanicEffectResult.RETRYABLE_FAILURE
        )
        val post = RecordingPostEffects()
        val coordinator = PanicRecoveryCoordinator(store, local, post)

        val result = coordinator.resumeLocalCritical()
        assertTrue(result is LocalRecoveryResult.StillPending)
        assertEquals(PanicPhase.LOCAL_PENDING, ready(store).phase)

        val postResult = coordinator.resumePostDestruction()
        assertTrue(postResult is PostRecoveryResult.LocalPhaseStillPending)
        assertEquals(0, post.remoteCalls)
    }

    @Test
    fun failed_access_drain_never_attempts_key_destruction() = runTest {
        val store = pendingStore()
        val local = RecordingLocalEffects(
            invalidate = PanicEffectResult.RETRYABLE_FAILURE,
            keys = PanicEffectResult.COMPLETED
        )
        val coordinator = PanicRecoveryCoordinator(store, local, RecordingPostEffects())

        val result = coordinator.resumeLocalCritical() as LocalRecoveryResult.StillPending

        assertEquals(PanicEffectResult.RETRYABLE_FAILURE, result.accessInvalidation)
        assertEquals(PanicEffectResult.NOT_ATTEMPTED, result.keyDestruction)
        assertEquals(0, local.keyCalls)
        assertEquals(PanicPhase.LOCAL_PENDING, ready(store).phase)
    }

    @Test
    fun successful_local_phase_checkpoints_before_any_post_effect() = runTest {
        val store = pendingStore()
        val local = RecordingLocalEffects()
        val post = RecordingPostEffects()
        val coordinator = PanicRecoveryCoordinator(store, local, post)

        assertEquals(
            LocalRecoveryResult.AdvancedToPostDestruction,
            coordinator.resumeLocalCritical()
        )
        assertEquals(PanicPhase.POST_PENDING, ready(store).phase)
        assertEquals(0, post.remoteCalls)
    }

    @Test
    fun cleanup_failure_does_not_block_remote_delete() = runTest {
        val store = pendingStore()
        val local = RecordingLocalEffects()
        val post = RecordingPostEffects(
            purge = PanicEffectResult.RETRYABLE_FAILURE,
            revoke = PanicEffectResult.COMPLETED,
            remote = PanicEffectResult.COMPLETED
        )
        val coordinator = PanicRecoveryCoordinator(store, local, post)
        coordinator.resumeLocalCritical()

        val result = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(PanicPhase.POST_PENDING, result.report.phase)
        val state = ready(store)
        assertFalse(state.purgeComplete)
        assertTrue(state.sessionRevocationComplete)
        assertEquals(RemoteDeleteCheckpoint.NOT_CONFIGURED, state.remoteDeleteCheckpoint)
        assertEquals(0, post.remoteCalls)

        post.purge = PanicEffectResult.COMPLETED
        val retried = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(PanicPhase.COMPLETE, retried.report.phase)
    }

    @Test
    fun commit_failure_after_local_effects_keeps_gate_closed_and_replays_safely() = runTest {
        val store = pendingStore()
        val gate = PanicAccessGate(store)
        val local = RecordingLocalEffects()
        val coordinator = PanicRecoveryCoordinator(store, local, RecordingPostEffects())

        store.failNextCommit = true
        val result = coordinator.resumeLocalCritical()
        assertTrue(result is LocalRecoveryResult.StorageUnavailable)
        assertEquals(PanicPhase.LOCAL_PENDING, ready(store).phase)
        assertTrue(gate.check() is VaultAccessDecision.Blocked)

        assertEquals(
            LocalRecoveryResult.AdvancedToPostDestruction,
            coordinator.resumeLocalCritical()
        )
        assertEquals(2, local.keyCalls)
    }

    @Test
    fun missing_or_corrupt_store_never_opens_gate() = runTest {
        val missing = InMemoryPanicStateStore(initial = null)
        assertTrue(PanicAccessGate(missing).check() is VaultAccessDecision.Blocked)

        val corrupt = InMemoryPanicStateStore()
        corrupt.unavailableFailure = PanicStoreFailure.CORRUPT
        assertTrue(PanicAccessGate(corrupt).check() is VaultAccessDecision.Blocked)
    }

    private suspend fun ready(store: InMemoryPanicStateStore): PanicPersistentState =
        (store.read() as PanicStoreReadResult.Ready).state

    private fun pendingStore(): InMemoryPanicStateStore =
        InMemoryPanicStateStore(
            PanicPersistentState(
                phase = PanicPhase.LOCAL_PENDING,
                panicIdHex = "c".repeat(64),
                remoteDeleteConfiguration = RemoteDeleteConfiguration.NOT_CONFIGURED,
                remoteDeleteCheckpoint = RemoteDeleteCheckpoint.NOT_CONFIGURED,
            )
        )
}

private class RecordingLocalEffects(
    private val invalidate: PanicEffectResult = PanicEffectResult.COMPLETED,
    private val keys: PanicEffectResult = PanicEffectResult.COMPLETED
) : LocalCriticalPanicEffects {
    var invalidationCalls = 0
    var keyCalls = 0

    override suspend fun invalidateInFlightAccess(): PanicEffectResult {
        invalidationCalls++
        return invalidate
    }

    override suspend fun destroyLocalReadCapability(): PanicEffectResult {
        keyCalls++
        return keys
    }
}

private class RecordingPostEffects(
    var purge: PanicEffectResult = PanicEffectResult.COMPLETED,
    var revoke: PanicEffectResult = PanicEffectResult.COMPLETED,
    var remote: PanicEffectResult = PanicEffectResult.COMPLETED
) : PostDestructionPanicEffects {
    var purgeCalls = 0
    var revokeCalls = 0
    var remoteCalls = 0

    override suspend fun purgePrivateStaging(): PanicEffectResult {
        purgeCalls++
        return purge
    }

    override suspend fun revokeDedicatedSessions(): PanicEffectResult {
        revokeCalls++
        return revoke
    }

    override suspend fun deleteRemoteVault(): PanicEffectResult {
        remoteCalls++
        return remote
    }
}
