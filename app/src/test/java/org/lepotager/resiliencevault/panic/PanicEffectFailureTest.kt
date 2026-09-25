package org.lepotager.resiliencevault.panic

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PanicEffectFailureTest {
    private fun store() = InMemoryPanicStateStore(
        PanicPersistentState.localPendingWithoutRemoteProof("a".repeat(64))
    )

    @Test
    fun legacy_unproven_remote_never_uses_generic_delete_effect() = runTest {
        val state = store()
        var deletes = 0
        val post = object : PostDestructionPanicEffects {
            override suspend fun purgePrivateStaging(): PanicEffectResult = throw IOException()
            override suspend fun revokeDedicatedSessions(): PanicEffectResult = awaitCancellation()
            override suspend fun deleteRemoteVault(): PanicEffectResult { deletes++; return PanicEffectResult.COMPLETED }
        }
        val coordinator = PanicRecoveryCoordinator(state, SuccessfulLocal(), post)
        coordinator.resumeLocalCritical()
        val result = coordinator.resumePostDestruction() as PostRecoveryResult.Progress
        assertEquals(PanicEffectResult.RETRYABLE_FAILURE, result.report.purge)
        assertEquals(PanicEffectResult.RETRYABLE_FAILURE, result.report.sessionRevocation)
        assertEquals(0, deletes)
        assertEquals(PanicEffectResult.NOT_ATTEMPTED, result.report.remoteDelete)
        assertEquals(PanicPhase.POST_PENDING, result.report.phase)
    }

    @Test
    fun caller_cancellation_is_not_swallowed() = runTest {
        val state = store()
        var keys = 0
        val local = object : LocalCriticalPanicEffects {
            override suspend fun invalidateInFlightAccess(): PanicEffectResult = throw CancellationException()
            override suspend fun destroyLocalReadCapability(): PanicEffectResult { keys++; return PanicEffectResult.COMPLETED }
        }
        val coordinator = PanicRecoveryCoordinator(state, local, NoPost())
        var cancelled = false
        try { coordinator.resumeLocalCritical() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, keys)
        assertEquals(PanicPhase.LOCAL_PENDING, (state.read() as PanicStoreReadResult.Ready).state.phase)
    }

    @Test
    fun key_exception_keeps_local_pending_and_network_blocked() = runTest {
        val state = store()
        val local = object : LocalCriticalPanicEffects {
            override suspend fun invalidateInFlightAccess() = PanicEffectResult.COMPLETED
            override suspend fun destroyLocalReadCapability(): PanicEffectResult = throw IOException()
        }
        val coordinator = PanicRecoveryCoordinator(state, local, NoPost())
        assertTrue(coordinator.resumeLocalCritical() is LocalRecoveryResult.StillPending)
        assertEquals(PostRecoveryResult.LocalPhaseStillPending, coordinator.resumePostDestruction())
    }

    private class SuccessfulLocal : LocalCriticalPanicEffects {
        override suspend fun invalidateInFlightAccess() = PanicEffectResult.COMPLETED
        override suspend fun destroyLocalReadCapability() = PanicEffectResult.COMPLETED
    }
    private class NoPost : PostDestructionPanicEffects {
        override suspend fun purgePrivateStaging(): PanicEffectResult = error("must not run")
        override suspend fun revokeDedicatedSessions(): PanicEffectResult = error("must not run")
        override suspend fun deleteRemoteVault(): PanicEffectResult = error("must not run")
    }
}
