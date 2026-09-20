package org.lepotager.resiliencevault.panic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InMemoryPanicStateStore(
    initial: PanicPersistentState? = PanicPersistentState.initial()
) : PanicStateStore {
    private val mutex = Mutex()
    private var state: PanicPersistentState? = initial

    var failNextCommit: Boolean = false
    var unavailableFailure: PanicStoreFailure? = null

    override suspend fun read(): PanicStoreReadResult = mutex.withLock {
        unavailableFailure?.let { return@withLock PanicStoreReadResult.Unavailable(it) }
        state?.let(PanicStoreReadResult::Ready)
            ?: PanicStoreReadResult.Unavailable(PanicStoreFailure.MISSING)
    }

    override suspend fun initializeEmptyIfMissing(): PanicStoreReadResult = mutex.withLock {
        unavailableFailure?.let { return@withLock PanicStoreReadResult.Unavailable(it) }
        if (state == null) state = PanicPersistentState.initial()
        PanicStoreReadResult.Ready(state!!)
    }

    override suspend fun <T> transaction(
        transform: (PanicPersistentState) -> PanicStateMutation<T>
    ): PanicTransactionResult<T> = mutex.withLock {
        unavailableFailure?.let {
            return@withLock PanicTransactionResult.Unavailable(it)
        }
        val current = state
            ?: return@withLock PanicTransactionResult.Unavailable(PanicStoreFailure.MISSING)

        when (val mutation = transform(current)) {
            is PanicStateMutation.Keep ->
                PanicTransactionResult.Success(mutation.value, current, false)

            is PanicStateMutation.Replace -> {
                if (failNextCommit) {
                    failNextCommit = false
                    return@withLock PanicTransactionResult.Unavailable(PanicStoreFailure.COMMIT_FAILED)
                }
                mutation.state.validate()
                state = mutation.state
                PanicTransactionResult.Success(mutation.value, mutation.state, true)
            }
        }
    }
}
