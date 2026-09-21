package org.lepotager.resiliencevault.panic

enum class PanicStoreFailure {
    MISSING,
    CORRUPT,
    IO_ERROR,
    COMMIT_FAILED
}

sealed interface PanicStoreReadResult {
    data class Ready(val state: PanicPersistentState) : PanicStoreReadResult
    data class Unavailable(val failure: PanicStoreFailure) : PanicStoreReadResult
}

sealed interface PanicStateMutation<out T> {
    data class Keep<T>(val value: T) : PanicStateMutation<T>
    data class Replace<T>(
        val state: PanicPersistentState,
        val value: T
    ) : PanicStateMutation<T>
}

sealed interface PanicTransactionResult<out T> {
    data class Success<T>(
        val value: T,
        val state: PanicPersistentState,
        val wroteState: Boolean
    ) : PanicTransactionResult<T>

    data class Unavailable(
        val failure: PanicStoreFailure
    ) : PanicTransactionResult<Nothing>
}

interface PanicStateStore {
    suspend fun read(): PanicStoreReadResult

    suspend fun <T> transaction(
        transform: (PanicPersistentState) -> PanicStateMutation<T>
    ): PanicTransactionResult<T>
}
