package org.lepotager.resiliencevault.panic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

enum class PanicEffectResult {
    COMPLETED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    NOT_ATTEMPTED
}

enum class RemoteDeleteRetryReason {
    NO_NETWORK,
    RATE_LIMITED,
    SERVER_ERROR,
    PROTOCOL_AMBIGUOUS,
    TIMEOUT,
    ADAPTER_UNAVAILABLE,
}

sealed interface RemoteDeleteAttempt {
    data object Complete : RemoteDeleteAttempt
    data object TombstonedPending : RemoteDeleteAttempt
    data object BlockedAuth : RemoteDeleteAttempt
    data class RetryableFailure(
        val reason: RemoteDeleteRetryReason,
    ) : RemoteDeleteAttempt
}

interface LocalCriticalPanicEffects {
    suspend fun invalidateInFlightAccess(): PanicEffectResult
    suspend fun destroyLocalReadCapability(): PanicEffectResult
}

interface PostDestructionPanicEffects {
    suspend fun purgePrivateStaging(): PanicEffectResult
    suspend fun revokeDedicatedSessions(): PanicEffectResult
    suspend fun deleteRemoteVault(intent: RemoteDeleteIntent): RemoteDeleteAttempt
}

sealed interface LocalRecoveryResult {
    data object NoPanic : LocalRecoveryResult
    data object AlreadyPastLocalPhase : LocalRecoveryResult
    data object AdvancedToPostDestruction : LocalRecoveryResult
    data class StillPending(
        val accessInvalidation: PanicEffectResult,
        val keyDestruction: PanicEffectResult
    ) : LocalRecoveryResult
    data class StorageUnavailable(val failure: PanicStoreFailure) : LocalRecoveryResult
}

data class PostRecoveryReport(
    val phase: PanicPhase,
    val purge: PanicEffectResult?,
    val sessionRevocation: PanicEffectResult?,
    val remoteDeleteAttempt: RemoteDeleteAttempt?,
    val remoteDeleteCheckpoint: RemoteDeleteCheckpoint?,
)

sealed interface PostRecoveryResult {
    data object NoPanic : PostRecoveryResult
    data object LocalPhaseStillPending : PostRecoveryResult
    data class Progress(val report: PostRecoveryReport) : PostRecoveryResult
    data class StorageUnavailable(val failure: PanicStoreFailure) : PostRecoveryResult
}

class PanicRecoveryCoordinator(
    private val store: PanicStateStore,
    private val localEffects: LocalCriticalPanicEffects,
    private val postEffects: PostDestructionPanicEffects
) {
    suspend fun resumeLocalCritical(): LocalRecoveryResult {
        val state = when (val read = store.read()) {
            is PanicStoreReadResult.Unavailable ->
                return LocalRecoveryResult.StorageUnavailable(read.failure)
            is PanicStoreReadResult.Ready -> read.state
        }

        when (state.phase) {
            PanicPhase.IDLE -> return LocalRecoveryResult.NoPanic
            PanicPhase.POST_PENDING,
            PanicPhase.COMPLETE,
            PanicPhase.LEGACY_COMPLETE_UNVERIFIED ->
                return LocalRecoveryResult.AlreadyPastLocalPhase
            PanicPhase.LOCAL_PENDING -> Unit
        }

        val access = attemptEffect(2_000L) { localEffects.invalidateInFlightAccess() }
        if (access != PanicEffectResult.COMPLETED) {
            return LocalRecoveryResult.StillPending(
                accessInvalidation = access,
                keyDestruction = PanicEffectResult.NOT_ATTEMPTED
            )
        }

        val keys = attemptEffect(2_000L) { localEffects.destroyLocalReadCapability() }
        if (keys != PanicEffectResult.COMPLETED) {
            return LocalRecoveryResult.StillPending(access, keys)
        }

        val panicId = state.panicIdHex
        return when (val checkpoint = store.transaction { latest ->
            if (latest.phase != PanicPhase.LOCAL_PENDING || latest.panicIdHex != panicId) {
                PanicStateMutation.Keep(false)
            } else {
                PanicStateMutation.Replace(
                    latest.copy(phase = PanicPhase.POST_PENDING),
                    true
                )
            }
        }) {
            is PanicTransactionResult.Unavailable ->
                LocalRecoveryResult.StorageUnavailable(checkpoint.failure)

            is PanicTransactionResult.Success ->
                if (checkpoint.value) {
                    LocalRecoveryResult.AdvancedToPostDestruction
                } else {
                    LocalRecoveryResult.AlreadyPastLocalPhase
                }
        }
    }

    suspend fun resumePostDestruction(): PostRecoveryResult {
        var state = when (val read = store.read()) {
            is PanicStoreReadResult.Unavailable ->
                return PostRecoveryResult.StorageUnavailable(read.failure)
            is PanicStoreReadResult.Ready -> read.state
        }

        when (state.phase) {
            PanicPhase.IDLE -> return PostRecoveryResult.NoPanic
            PanicPhase.LOCAL_PENDING -> return PostRecoveryResult.LocalPhaseStillPending
            PanicPhase.COMPLETE,
            PanicPhase.LEGACY_COMPLETE_UNVERIFIED ->
                return PostRecoveryResult.Progress(
                    report(state, null, null, null)
                )
            PanicPhase.POST_PENDING -> Unit
        }

        var purgeResult: PanicEffectResult? = null
        var revokeResult: PanicEffectResult? = null
        var remoteResult: RemoteDeleteAttempt? = null

        if (!state.purgeComplete) {
            purgeResult = attemptEffect(10_000L) { postEffects.purgePrivateStaging() }
            if (purgeResult == PanicEffectResult.COMPLETED) {
                state = checkpointPostTask(state, PostTask.PURGE)
                    ?: return PostRecoveryResult.StorageUnavailable(PanicStoreFailure.COMMIT_FAILED)
            }
        }

        if (!state.sessionRevocationComplete) {
            revokeResult = attemptEffect(10_000L) { postEffects.revokeDedicatedSessions() }
            if (revokeResult == PanicEffectResult.COMPLETED) {
                state = checkpointPostTask(state, PostTask.REVOKE)
                    ?: return PostRecoveryResult.StorageUnavailable(PanicStoreFailure.COMMIT_FAILED)
            }
        }

        when (state.remoteDeleteCheckpoint) {
            RemoteDeleteCheckpoint.PENDING,
            RemoteDeleteCheckpoint.TOMBSTONED_PENDING -> {
                val intent = checkNotNull(state.remoteDeleteIntent)
                remoteResult = attemptRemoteDelete(10_000L) {
                    postEffects.deleteRemoteVault(intent)
                }
                val nextCheckpoint = when (remoteResult) {
                    RemoteDeleteAttempt.Complete -> RemoteDeleteCheckpoint.COMPLETE
                    RemoteDeleteAttempt.TombstonedPending ->
                        RemoteDeleteCheckpoint.TOMBSTONED_PENDING
                    RemoteDeleteAttempt.BlockedAuth -> RemoteDeleteCheckpoint.BLOCKED_AUTH
                    is RemoteDeleteAttempt.RetryableFailure -> null
                    null -> null
                }
                if (nextCheckpoint != null && nextCheckpoint != state.remoteDeleteCheckpoint) {
                    state = checkpointRemoteDelete(state, nextCheckpoint)
                        ?: return PostRecoveryResult.StorageUnavailable(
                            PanicStoreFailure.COMMIT_FAILED
                        )
                }
            }

            RemoteDeleteCheckpoint.NOT_CONFIGURED,
            RemoteDeleteCheckpoint.BLOCKED_AUTH,
            RemoteDeleteCheckpoint.COMPLETE,
            RemoteDeleteCheckpoint.LEGACY_UNPROVEN,
            null -> Unit
        }

        if (
            state.purgeComplete &&
                state.sessionRevocationComplete &&
                state.remoteDeleteAllowsV2Completion()
        ) {
            when (val complete = store.transaction { latest ->
                if (
                    latest.phase == PanicPhase.POST_PENDING &&
                    latest.panicIdHex == state.panicIdHex &&
                    latest.purgeComplete &&
                    latest.sessionRevocationComplete &&
                    latest.remoteDeleteAllowsV2Completion()
                ) {
                    PanicStateMutation.Replace(latest.copy(phase = PanicPhase.COMPLETE), true)
                } else {
                    PanicStateMutation.Keep(false)
                }
            }) {
                is PanicTransactionResult.Unavailable ->
                    return PostRecoveryResult.StorageUnavailable(complete.failure)
                is PanicTransactionResult.Success -> {
                    if (complete.value) state = complete.state
                }
            }
        }

        return PostRecoveryResult.Progress(
            report(state, purgeResult, revokeResult, remoteResult)
        )
    }

    private fun report(
        state: PanicPersistentState,
        purge: PanicEffectResult?,
        revoke: PanicEffectResult?,
        remote: RemoteDeleteAttempt?,
    ) = PostRecoveryReport(
        phase = state.phase,
        purge = purge,
        sessionRevocation = revoke,
        remoteDeleteAttempt = remote,
        remoteDeleteCheckpoint = state.remoteDeleteCheckpoint,
    )

    // Cooperative timeouts do not interrupt blocking platform I/O; adapters must be bounded too.
    private suspend fun attemptEffect(
        timeoutMs: Long,
        action: suspend () -> PanicEffectResult
    ): PanicEffectResult =
        withTimeoutOrNull(timeoutMs) {
            try {
                val result = action()
                currentCoroutineContext().ensureActive()
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PanicEffectResult.RETRYABLE_FAILURE
            }
        } ?: PanicEffectResult.RETRYABLE_FAILURE

    private suspend fun attemptRemoteDelete(
        timeoutMs: Long,
        action: suspend () -> RemoteDeleteAttempt,
    ): RemoteDeleteAttempt =
        withTimeoutOrNull(timeoutMs) {
            try {
                val result = action()
                currentCoroutineContext().ensureActive()
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RemoteDeleteAttempt.RetryableFailure(
                    RemoteDeleteRetryReason.ADAPTER_UNAVAILABLE
                )
            }
        } ?: RemoteDeleteAttempt.RetryableFailure(RemoteDeleteRetryReason.TIMEOUT)

    private enum class PostTask {
        PURGE,
        REVOKE
    }

    private suspend fun checkpointPostTask(
        expected: PanicPersistentState,
        task: PostTask
    ): PanicPersistentState? =
        when (val checkpoint = store.transaction { latest ->
            if (
                latest.phase != PanicPhase.POST_PENDING ||
                latest.panicIdHex != expected.panicIdHex
            ) {
                PanicStateMutation.Keep(false)
            } else {
                val next = when (task) {
                    PostTask.PURGE -> latest.copy(purgeComplete = true)
                    PostTask.REVOKE -> latest.copy(sessionRevocationComplete = true)
                }
                PanicStateMutation.Replace(next, true)
            }
        }) {
            is PanicTransactionResult.Unavailable -> null
            is PanicTransactionResult.Success -> checkpoint.state
        }

    private suspend fun checkpointRemoteDelete(
        expected: PanicPersistentState,
        nextCheckpoint: RemoteDeleteCheckpoint,
    ): PanicPersistentState? =
        when (val checkpoint = store.transaction { latest ->
            if (
                latest.phase != PanicPhase.POST_PENDING ||
                latest.panicIdHex != expected.panicIdHex ||
                latest.remoteDeleteIntent != expected.remoteDeleteIntent ||
                latest.remoteDeleteCheckpoint != expected.remoteDeleteCheckpoint
            ) {
                PanicStateMutation.Keep(false)
            } else {
                PanicStateMutation.Replace(
                    latest.copy(remoteDeleteCheckpoint = nextCheckpoint),
                    true,
                )
            }
        }) {
            is PanicTransactionResult.Unavailable -> null
            is PanicTransactionResult.Success ->
                if (checkpoint.value) checkpoint.state else checkpoint.state
        }
}
