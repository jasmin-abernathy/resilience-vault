package org.lepotager.resiliencevault.panic

enum class PanicEffectResult {
    COMPLETED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE
}

interface LocalCriticalPanicEffects {
    suspend fun invalidateInFlightAccess(): PanicEffectResult
    suspend fun destroyLocalReadCapability(): PanicEffectResult
}

interface PostDestructionPanicEffects {
    suspend fun purgePrivateStaging(): PanicEffectResult
    suspend fun revokeDedicatedSessions(): PanicEffectResult
    suspend fun deleteRemoteVault(): PanicEffectResult
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
    val remoteDelete: PanicEffectResult?
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
            PanicPhase.POST_PENDING, PanicPhase.COMPLETE ->
                return LocalRecoveryResult.AlreadyPastLocalPhase
            PanicPhase.LOCAL_PENDING -> Unit
        }

        val access = localEffects.invalidateInFlightAccess()
        val keys = localEffects.destroyLocalReadCapability()
        if (access != PanicEffectResult.COMPLETED || keys != PanicEffectResult.COMPLETED) {
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
            PanicPhase.COMPLETE ->
                return PostRecoveryResult.Progress(
                    PostRecoveryReport(PanicPhase.COMPLETE, null, null, null)
                )
            PanicPhase.POST_PENDING -> Unit
        }

        var purgeResult: PanicEffectResult? = null
        var revokeResult: PanicEffectResult? = null
        var remoteResult: PanicEffectResult? = null

        if (!state.purgeComplete) {
            purgeResult = postEffects.purgePrivateStaging()
            if (purgeResult == PanicEffectResult.COMPLETED) {
                state = checkpointPostTask(state, PostTask.PURGE)
                    ?: return PostRecoveryResult.StorageUnavailable(PanicStoreFailure.COMMIT_FAILED)
            }
        }

        if (!state.sessionRevocationComplete) {
            revokeResult = postEffects.revokeDedicatedSessions()
            if (revokeResult == PanicEffectResult.COMPLETED) {
                state = checkpointPostTask(state, PostTask.REVOKE)
                    ?: return PostRecoveryResult.StorageUnavailable(PanicStoreFailure.COMMIT_FAILED)
            }
        }

        if (!state.remoteDeleteComplete) {
            remoteResult = postEffects.deleteRemoteVault()
            if (remoteResult == PanicEffectResult.COMPLETED) {
                state = checkpointPostTask(state, PostTask.REMOTE_DELETE)
                    ?: return PostRecoveryResult.StorageUnavailable(PanicStoreFailure.COMMIT_FAILED)
            }
        }

        if (state.purgeComplete && state.sessionRevocationComplete && state.remoteDeleteComplete) {
            when (val complete = store.transaction { latest ->
                if (latest.phase == PanicPhase.POST_PENDING &&
                    latest.panicIdHex == state.panicIdHex &&
                    latest.purgeComplete &&
                    latest.sessionRevocationComplete &&
                    latest.remoteDeleteComplete
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
            PostRecoveryReport(
                phase = state.phase,
                purge = purgeResult,
                sessionRevocation = revokeResult,
                remoteDelete = remoteResult
            )
        )
    }

    private enum class PostTask {
        PURGE,
        REVOKE,
        REMOTE_DELETE
    }

    private suspend fun checkpointPostTask(
        expected: PanicPersistentState,
        task: PostTask
    ): PanicPersistentState? =
        when (val checkpoint = store.transaction { latest ->
            if (latest.phase != PanicPhase.POST_PENDING ||
                latest.panicIdHex != expected.panicIdHex
            ) {
                PanicStateMutation.Keep(false)
            } else {
                val next = when (task) {
                    PostTask.PURGE -> latest.copy(purgeComplete = true)
                    PostTask.REVOKE -> latest.copy(sessionRevocationComplete = true)
                    PostTask.REMOTE_DELETE -> latest.copy(remoteDeleteComplete = true)
                }
                PanicStateMutation.Replace(next, true)
            }
        }) {
            is PanicTransactionResult.Unavailable -> null
            is PanicTransactionResult.Success -> checkpoint.state
        }
}
