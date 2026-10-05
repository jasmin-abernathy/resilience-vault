package org.lepotager.resiliencevault.panic

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanicEffectFailureTest {
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
    fun legacy_unproven_remote_never_uses_delete_effect() = runTest {
        val state = InMemoryPanicStateStore(
            PanicPersistentState.localPendingWithoutRemoteProof("a".repeat(64))
        )
        var deletes = 0
        val post = object : PostDestructionPanicEffects {
            override suspend fun purgePrivateStaging(): PanicEffectResult = throw IOException()
            override suspend fun revokeDedicatedSessions(): PanicEffectResult = awaitCancellation()
            override suspend fun deleteRemoteVault(intent: RemoteDeleteIntent): RemoteDeleteAttempt {
                deletes++
                return RemoteDeleteAttempt.Complete
            }
        }
        val coordinator = PanicRecoveryCoordinator(state, SuccessfulLocal(), post)
        coordinator.resumeLocalCritical()
        val result = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(PanicEffectResult.RETRYABLE_FAILURE, result.report.purge)
        assertEquals(PanicEffectResult.RETRYABLE_FAILURE, result.report.sessionRevocation)
        assertEquals(0, deletes)
        assertEquals(RemoteDeleteCheckpoint.LEGACY_UNPROVEN, result.report.remoteDeleteCheckpoint)
        assertEquals(PanicPhase.POST_PENDING, result.report.phase)
    }

    @Test
    fun remote_exception_is_retryable_and_never_checkpointed() = runTest {
        val state = configuredPostStore()
        val post = object : PostDestructionPanicEffects {
            override suspend fun purgePrivateStaging() = PanicEffectResult.COMPLETED
            override suspend fun revokeDedicatedSessions() = PanicEffectResult.COMPLETED
            override suspend fun deleteRemoteVault(intent: RemoteDeleteIntent): RemoteDeleteAttempt =
                throw IOException("backend absent")
        }
        val result = PanicRecoveryCoordinator(
            state,
            SuccessfulLocal(),
            post,
        ).resumePostDestruction() as PostRecoveryResult.Progress

        assertEquals(
            RemoteDeleteAttempt.RetryableFailure(RemoteDeleteRetryReason.ADAPTER_UNAVAILABLE),
            result.report.remoteDeleteAttempt,
        )
        assertEquals(RemoteDeleteCheckpoint.PENDING, result.report.remoteDeleteCheckpoint)
    }

    @Test
    fun remote_timeout_is_retryable_and_never_checkpointed() = runTest {
        val state = configuredPostStore()
        val post = object : PostDestructionPanicEffects {
            override suspend fun purgePrivateStaging() = PanicEffectResult.COMPLETED
            override suspend fun revokeDedicatedSessions() = PanicEffectResult.COMPLETED
            override suspend fun deleteRemoteVault(intent: RemoteDeleteIntent): RemoteDeleteAttempt {
                awaitCancellation()
            }
        }
        val result = PanicRecoveryCoordinator(
            state,
            SuccessfulLocal(),
            post,
        ).resumePostDestruction() as PostRecoveryResult.Progress

        assertEquals(
            RemoteDeleteAttempt.RetryableFailure(RemoteDeleteRetryReason.TIMEOUT),
            result.report.remoteDeleteAttempt,
        )
        assertEquals(RemoteDeleteCheckpoint.PENDING, result.report.remoteDeleteCheckpoint)
    }

    @Test
    fun caller_cancellation_is_not_swallowed_in_local_or_remote_phase() = runTest {
        val localState = InMemoryPanicStateStore(
            PanicPersistentState.localPendingWithoutRemoteProof("b".repeat(64))
        )
        var keys = 0
        val local = object : LocalCriticalPanicEffects {
            override suspend fun invalidateInFlightAccess(): PanicEffectResult =
                throw CancellationException()
            override suspend fun destroyLocalReadCapability(): PanicEffectResult {
                keys++
                return PanicEffectResult.COMPLETED
            }
        }
        val localCoordinator = PanicRecoveryCoordinator(localState, local, NoPost())
        var localCancelled = false
        try {
            localCoordinator.resumeLocalCritical()
        } catch (_: CancellationException) {
            localCancelled = true
        }
        assertTrue(localCancelled)
        assertEquals(0, keys)

        val remoteState = configuredPostStore()
        val remotePost = object : PostDestructionPanicEffects {
            override suspend fun purgePrivateStaging() = PanicEffectResult.COMPLETED
            override suspend fun revokeDedicatedSessions() = PanicEffectResult.COMPLETED
            override suspend fun deleteRemoteVault(intent: RemoteDeleteIntent): RemoteDeleteAttempt =
                throw CancellationException()
        }
        var remoteCancelled = false
        try {
            PanicRecoveryCoordinator(
                remoteState,
                SuccessfulLocal(),
                remotePost,
            ).resumePostDestruction()
        } catch (_: CancellationException) {
            remoteCancelled = true
        }
        assertTrue(remoteCancelled)
        assertEquals(
            RemoteDeleteCheckpoint.PENDING,
            (remoteState.read() as PanicStoreReadResult.Ready).state.remoteDeleteCheckpoint,
        )
    }

    private fun configuredPostStore(): InMemoryPanicStateStore =
        InMemoryPanicStateStore(
            PanicPersistentState(
                phase = PanicPhase.POST_PENDING,
                panicIdHex = "c".repeat(64),
                purgeComplete = true,
                sessionRevocationComplete = true,
                remoteDeleteConfiguration = RemoteDeleteConfiguration.CONFIGURED,
                remoteDeleteCheckpoint = RemoteDeleteCheckpoint.PENDING,
                remoteDeleteIntent = intent,
            )
        )

    private class SuccessfulLocal : LocalCriticalPanicEffects {
        override suspend fun invalidateInFlightAccess() = PanicEffectResult.COMPLETED
        override suspend fun destroyLocalReadCapability() = PanicEffectResult.COMPLETED
    }

    private class NoPost : PostDestructionPanicEffects {
        override suspend fun purgePrivateStaging(): PanicEffectResult = error("must not run")
        override suspend fun revokeDedicatedSessions(): PanicEffectResult = error("must not run")
        override suspend fun deleteRemoteVault(intent: RemoteDeleteIntent): RemoteDeleteAttempt =
            error("must not run")
    }
}
