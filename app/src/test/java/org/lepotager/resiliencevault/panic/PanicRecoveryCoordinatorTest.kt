package org.lepotager.resiliencevault.panic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanicRecoveryCoordinatorTest {
    private val intent = RemoteDeleteIntent(
        capsuleFormatVersion = 1,
        capsuleIdHex = "1".repeat(32),
        tenantId = "tenant-1",
        vaultIdHex = "2".repeat(64),
        vaultGenerationHex = "3".repeat(64),
        serviceId = "primary",
        capsuleSha256Hex = "4".repeat(64),
    )

    @Test
    fun key_failure_never_enters_post_phase_or_calls_network() = runTest {
        val store = configuredPendingStore()
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
    fun successful_local_phase_checkpoints_before_any_post_effect() = runTest {
        val store = configuredPendingStore()
        val post = RecordingPostEffects()
        val coordinator = PanicRecoveryCoordinator(store, RecordingLocalEffects(), post)

        assertEquals(
            LocalRecoveryResult.AdvancedToPostDestruction,
            coordinator.resumeLocalCritical()
        )
        assertEquals(PanicPhase.POST_PENDING, ready(store).phase)
        assertEquals(0, post.remoteCalls)
    }

    @Test
    fun complete_remote_checkpoint_is_independent_from_cleanup_and_finishes_later() = runTest {
        val store = configuredPendingStore()
        val post = RecordingPostEffects(
            purge = PanicEffectResult.RETRYABLE_FAILURE,
            revoke = PanicEffectResult.COMPLETED,
            remote = RemoteDeleteAttempt.Complete,
        )
        val coordinator = PanicRecoveryCoordinator(store, RecordingLocalEffects(), post)
        coordinator.resumeLocalCritical()

        val first = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(PanicPhase.POST_PENDING, first.report.phase)
        val afterFirst = ready(store)
        assertFalse(afterFirst.purgeComplete)
        assertTrue(afterFirst.sessionRevocationComplete)
        assertEquals(RemoteDeleteCheckpoint.COMPLETE, afterFirst.remoteDeleteCheckpoint)
        assertEquals(1, post.remoteCalls)

        post.purge = PanicEffectResult.COMPLETED
        val second = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(PanicPhase.COMPLETE, second.report.phase)
        assertEquals(1, post.remoteCalls)
    }

    @Test
    fun tombstone_is_durable_and_transient_failure_does_not_erase_it() = runTest {
        val store = configuredPostStore(
            purge = true,
            revoke = true,
            checkpoint = RemoteDeleteCheckpoint.PENDING,
        )
        val post = RecordingPostEffects(remote = RemoteDeleteAttempt.TombstonedPending)
        val coordinator = PanicRecoveryCoordinator(store, RecordingLocalEffects(), post)

        val first = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(RemoteDeleteCheckpoint.TOMBSTONED_PENDING, first.report.remoteDeleteCheckpoint)
        assertEquals(RemoteDeleteAttempt.TombstonedPending, first.report.remoteDeleteAttempt)
        assertEquals(PanicPhase.POST_PENDING, first.report.phase)

        post.remote = RemoteDeleteAttempt.RetryableFailure(RemoteDeleteRetryReason.SERVER_ERROR)
        val second = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(RemoteDeleteCheckpoint.TOMBSTONED_PENDING, second.report.remoteDeleteCheckpoint)
        assertEquals(
            RemoteDeleteAttempt.RetryableFailure(RemoteDeleteRetryReason.SERVER_ERROR),
            second.report.remoteDeleteAttempt,
        )

        post.remote = RemoteDeleteAttempt.Complete
        val third = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(RemoteDeleteCheckpoint.COMPLETE, third.report.remoteDeleteCheckpoint)
        assertEquals(PanicPhase.COMPLETE, third.report.phase)
    }

    @Test
    fun blocked_auth_is_durable_and_suspends_automatic_retry() = runTest {
        val store = configuredPostStore(
            purge = true,
            revoke = true,
            checkpoint = RemoteDeleteCheckpoint.PENDING,
        )
        val post = RecordingPostEffects(remote = RemoteDeleteAttempt.BlockedAuth)
        val coordinator = PanicRecoveryCoordinator(store, RecordingLocalEffects(), post)

        val first = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(RemoteDeleteCheckpoint.BLOCKED_AUTH, first.report.remoteDeleteCheckpoint)
        assertEquals(1, post.remoteCalls)

        post.remote = RemoteDeleteAttempt.Complete
        val second = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(RemoteDeleteCheckpoint.BLOCKED_AUTH, second.report.remoteDeleteCheckpoint)
        assertEquals(PanicPhase.POST_PENDING, second.report.phase)
        assertEquals(1, post.remoteCalls)
    }

    @Test
    fun retryable_failure_leaves_pending_and_never_completes() = runTest {
        val store = configuredPostStore(
            purge = true,
            revoke = true,
            checkpoint = RemoteDeleteCheckpoint.PENDING,
        )
        val post = RecordingPostEffects(
            remote = RemoteDeleteAttempt.RetryableFailure(RemoteDeleteRetryReason.NO_NETWORK)
        )
        val result = PanicRecoveryCoordinator(
            store,
            RecordingLocalEffects(),
            post,
        ).resumePostDestruction() as PostRecoveryResult.Progress

        assertEquals(RemoteDeleteCheckpoint.PENDING, result.report.remoteDeleteCheckpoint)
        assertEquals(PanicPhase.POST_PENDING, result.report.phase)
    }

    @Test
    fun proven_not_configured_never_calls_remote_and_can_complete() = runTest {
        val store = InMemoryPanicStateStore(
            PanicPersistentState(
                phase = PanicPhase.POST_PENDING,
                panicIdHex = "c".repeat(64),
                purgeComplete = true,
                sessionRevocationComplete = true,
                remoteDeleteConfiguration = RemoteDeleteConfiguration.NOT_CONFIGURED,
                remoteDeleteCheckpoint = RemoteDeleteCheckpoint.NOT_CONFIGURED,
            )
        )
        val post = RecordingPostEffects()
        val result = PanicRecoveryCoordinator(
            store,
            RecordingLocalEffects(),
            post,
        ).resumePostDestruction() as PostRecoveryResult.Progress

        assertEquals(PanicPhase.COMPLETE, result.report.phase)
        assertEquals(0, post.remoteCalls)
    }

    @Test
    fun legacy_unproven_never_calls_remote_or_becomes_v2_complete() = runTest {
        val store = InMemoryPanicStateStore(
            PanicPersistentState.localPendingWithoutRemoteProof("d".repeat(64))
        )
        val post = RecordingPostEffects()
        val coordinator = PanicRecoveryCoordinator(store, RecordingLocalEffects(), post)
        coordinator.resumeLocalCritical()

        val result = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(PanicPhase.POST_PENDING, result.report.phase)
        assertEquals(RemoteDeleteCheckpoint.LEGACY_UNPROVEN, result.report.remoteDeleteCheckpoint)
        assertEquals(0, post.remoteCalls)
    }

    @Test
    fun commit_failure_after_remote_result_never_acknowledges_checkpoint() = runTest {
        val store = configuredPostStore(
            purge = true,
            revoke = true,
            checkpoint = RemoteDeleteCheckpoint.PENDING,
        )
        val post = RecordingPostEffects(remote = RemoteDeleteAttempt.Complete)
        val coordinator = PanicRecoveryCoordinator(store, RecordingLocalEffects(), post)

        store.failNextCommit = true
        assertTrue(coordinator.resumePostDestruction() is PostRecoveryResult.StorageUnavailable)
        assertEquals(RemoteDeleteCheckpoint.PENDING, ready(store).remoteDeleteCheckpoint)

        val retried = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(PanicPhase.COMPLETE, retried.report.phase)
        assertEquals(2, post.remoteCalls)
    }

    @Test
    fun terminal_legacy_complete_never_reopens_or_runs_effects() = runTest {
        val state = PanicPersistentState(
            phase = PanicPhase.LEGACY_COMPLETE_UNVERIFIED,
            panicIdHex = "e".repeat(64),
            purgeComplete = true,
            sessionRevocationComplete = true,
            remoteDeleteConfiguration = RemoteDeleteConfiguration.UNKNOWN,
            remoteDeleteCheckpoint = RemoteDeleteCheckpoint.LEGACY_UNPROVEN,
            legacyRemoteUnproven = true,
            legacyRemoteDeleteComplete = true,
        )
        val store = InMemoryPanicStateStore(state)
        val post = RecordingPostEffects()
        val coordinator = PanicRecoveryCoordinator(store, RecordingLocalEffects(), post)

        assertEquals(LocalRecoveryResult.AlreadyPastLocalPhase, coordinator.resumeLocalCritical())
        val result = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(PanicPhase.LEGACY_COMPLETE_UNVERIFIED, result.report.phase)
        assertEquals(0, post.remoteCalls)
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

    private fun configuredPendingStore(): InMemoryPanicStateStore =
        InMemoryPanicStateStore(
            PanicPersistentState(
                phase = PanicPhase.LOCAL_PENDING,
                panicIdHex = "c".repeat(64),
                remoteDeleteConfiguration = RemoteDeleteConfiguration.CONFIGURED,
                remoteDeleteCheckpoint = RemoteDeleteCheckpoint.PENDING,
                remoteDeleteIntent = intent,
            )
        )

    private fun configuredPostStore(
        purge: Boolean,
        revoke: Boolean,
        checkpoint: RemoteDeleteCheckpoint,
    ): InMemoryPanicStateStore =
        InMemoryPanicStateStore(
            PanicPersistentState(
                phase = PanicPhase.POST_PENDING,
                panicIdHex = "c".repeat(64),
                purgeComplete = purge,
                sessionRevocationComplete = revoke,
                remoteDeleteConfiguration = RemoteDeleteConfiguration.CONFIGURED,
                remoteDeleteCheckpoint = checkpoint,
                remoteDeleteIntent = intent,
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
    var remote: RemoteDeleteAttempt =
        RemoteDeleteAttempt.RetryableFailure(RemoteDeleteRetryReason.ADAPTER_UNAVAILABLE),
) : PostDestructionPanicEffects {
    var purgeCalls = 0
    var revokeCalls = 0
    var remoteCalls = 0
    var lastIntent: RemoteDeleteIntent? = null

    override suspend fun purgePrivateStaging(): PanicEffectResult {
        purgeCalls++
        return purge
    }

    override suspend fun revokeDedicatedSessions(): PanicEffectResult {
        revokeCalls++
        return revoke
    }

    override suspend fun deleteRemoteVault(intent: RemoteDeleteIntent): RemoteDeleteAttempt {
        remoteCalls++
        lastIntent = intent
        return remote
    }
}
