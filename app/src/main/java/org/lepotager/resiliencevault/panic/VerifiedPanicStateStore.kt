package org.lepotager.resiliencevault.panic

import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One instance per canonical path. File operations must be bounded and non-suspending. */
internal interface PanicStateFile {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}

internal sealed interface PanicInitializationResult {
    data object Created : PanicInitializationResult
    data object AlreadyInitialized : PanicInitializationResult
    data class Unavailable(val failure: PanicStoreFailure) : PanicInitializationResult
}

/** Testable transaction boundary. Missing state is fail-closed unless initializeFresh()
 * is called explicitly during the first-install ceremony. Corrupt/existing state is never reset.
 */
internal class VerifiedPanicStateStore(
    private val file: PanicStateFile,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : PanicStateStore {
    private val mutex = Mutex()
    private var failureLatch: PanicStoreFailure? = null

    override suspend fun read(): PanicStoreReadResult = withContext(dispatcher) {
        mutex.withLock { readUnlocked() }
    }

    suspend fun initializeFresh(): PanicInitializationResult = withContext(dispatcher) {
        mutex.withLock {
            when (failureLatch) {
                PanicStoreFailure.CORRUPT, PanicStoreFailure.IO_ERROR, PanicStoreFailure.COMMIT_FAILED ->
                    return@withLock PanicInitializationResult.Unavailable(checkNotNull(failureLatch))
                else -> Unit
            }
            try {
                PanicStateCodec.decode(file.read())
                failureLatch = null
                return@withLock PanicInitializationResult.AlreadyInitialized
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: FileNotFoundException) {
                // The only state that may be initialized. Never treat corruption/IO as absence.
            } catch (_: PanicStateCorruptionException) {
                failureLatch = PanicStoreFailure.CORRUPT
                return@withLock PanicInitializationResult.Unavailable(PanicStoreFailure.CORRUPT)
            } catch (_: Exception) {
                failureLatch = PanicStoreFailure.IO_ERROR
                return@withLock PanicInitializationResult.Unavailable(PanicStoreFailure.IO_ERROR)
            }

            val bytes = PanicStateCodec.encode(PanicPersistentState.initial())
            try {
                file.write(bytes)
                if (!file.read().contentEquals(bytes)) throw IOException("Initial state commit verification failed")
            } catch (cancelled: CancellationException) {
                failureLatch = PanicStoreFailure.COMMIT_FAILED
                throw cancelled
            } catch (_: Exception) {
                failureLatch = PanicStoreFailure.COMMIT_FAILED
                return@withLock PanicInitializationResult.Unavailable(PanicStoreFailure.COMMIT_FAILED)
            }
            failureLatch = null
            PanicInitializationResult.Created
        }
    }

    private fun unavailable(failure: PanicStoreFailure): PanicStoreReadResult.Unavailable {
        failureLatch = failure
        return PanicStoreReadResult.Unavailable(failure)
    }

    private fun readUnlocked(): PanicStoreReadResult {
        failureLatch?.let { return PanicStoreReadResult.Unavailable(it) }
        return try {
            PanicStoreReadResult.Ready(PanicStateCodec.decode(file.read()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FileNotFoundException) {
            unavailable(PanicStoreFailure.MISSING)
        } catch (_: PanicStateCorruptionException) {
            unavailable(PanicStoreFailure.CORRUPT)
        } catch (_: Exception) {
            unavailable(PanicStoreFailure.IO_ERROR)
        }
    }

    override suspend fun <T> transaction(
        transform: (PanicPersistentState) -> PanicStateMutation<T>
    ): PanicTransactionResult<T> = withContext(dispatcher) {
        mutex.withLock {
            val current = when (val read = readUnlocked()) {
                is PanicStoreReadResult.Ready -> read.state
                is PanicStoreReadResult.Unavailable ->
                    return@withLock PanicTransactionResult.Unavailable(read.failure)
            }
            when (val mutation = transform(current)) {
                is PanicStateMutation.Keep -> PanicTransactionResult.Success(mutation.value, current, false)
                is PanicStateMutation.Replace -> {
                    val bytes = try {
                        mutation.state.validateTransitionFrom(current)
                        PanicStateCodec.encode(mutation.state)
                    } catch (_: IllegalArgumentException) {
                        failureLatch = PanicStoreFailure.CORRUPT
                        return@withLock PanicTransactionResult.Unavailable(PanicStoreFailure.CORRUPT)
                    }
                    try {
                        file.write(bytes)
                        // Do not infer success solely from a void platform finishWrite().
                        if (!file.read().contentEquals(bytes)) throw IOException("State commit verification failed")
                    } catch (cancelled: CancellationException) {
                        failureLatch = PanicStoreFailure.COMMIT_FAILED
                        throw cancelled
                    } catch (_: Exception) {
                        failureLatch = PanicStoreFailure.COMMIT_FAILED
                        return@withLock PanicTransactionResult.Unavailable(PanicStoreFailure.COMMIT_FAILED)
                    }
                    PanicTransactionResult.Success(mutation.value, mutation.state, true)
                }
            }
        }
    }
}
