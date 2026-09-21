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

/** Testable transaction boundary; does not provision/reset a missing record. */
internal class VerifiedPanicStateStore(
    private val file: PanicStateFile,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : PanicStateStore {
    private val mutex = Mutex()
    private var failureLatch: PanicStoreFailure? = null

    override suspend fun read(): PanicStoreReadResult = withContext(dispatcher) {
        mutex.withLock { readUnlocked() }
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
