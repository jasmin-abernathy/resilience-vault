package org.lepotager.resiliencevault.panic

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AtomicFilePanicStateStore private constructor(
    private val file: AtomicFile,
    private val ioDispatcher: CoroutineDispatcher
) : PanicStateStore {
    private val mutex = Mutex()

    companion object {
        fun create(
            context: Context,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): AtomicFilePanicStateStore {
            val directory = File(context.noBackupFilesDir, "security")
            return AtomicFilePanicStateStore(
                file = AtomicFile(File(directory, "remote-panic-state.bin")),
                ioDispatcher = ioDispatcher
            )
        }
    }

    override suspend fun read(): PanicStoreReadResult = withContext(ioDispatcher) {
        mutex.withLock { readUnlocked() }
    }

    override suspend fun initializeEmptyIfMissing(): PanicStoreReadResult = withContext(ioDispatcher) {
        mutex.withLock {
            when (val current = readUnlocked()) {
                is PanicStoreReadResult.Ready -> current
                is PanicStoreReadResult.Unavailable -> {
                    if (current.failure != PanicStoreFailure.MISSING) {
                        current
                    } else {
                        val initial = PanicPersistentState.initial()
                        when (writeUnlocked(initial)) {
                            null -> PanicStoreReadResult.Ready(initial)
                            else -> PanicStoreReadResult.Unavailable(PanicStoreFailure.COMMIT_FAILED)
                        }
                    }
                }
            }
        }
    }

    override suspend fun <T> transaction(
        transform: (PanicPersistentState) -> PanicStateMutation<T>
    ): PanicTransactionResult<T> = withContext(ioDispatcher) {
        mutex.withLock {
            val current = when (val read = readUnlocked()) {
                is PanicStoreReadResult.Ready -> read.state
                is PanicStoreReadResult.Unavailable ->
                    return@withLock PanicTransactionResult.Unavailable(read.failure)
            }

            when (val mutation = transform(current)) {
                is PanicStateMutation.Keep ->
                    PanicTransactionResult.Success(
                        value = mutation.value,
                        state = current,
                        wroteState = false
                    )

                is PanicStateMutation.Replace -> {
                    try {
                        mutation.state.validate()
                    } catch (_: Throwable) {
                        return@withLock PanicTransactionResult.Unavailable(PanicStoreFailure.CORRUPT)
                    }

                    val failure = writeUnlocked(mutation.state)
                    if (failure != null) {
                        PanicTransactionResult.Unavailable(failure)
                    } else {
                        PanicTransactionResult.Success(
                            value = mutation.value,
                            state = mutation.state,
                            wroteState = true
                        )
                    }
                }
            }
        }
    }

    private fun readUnlocked(): PanicStoreReadResult =
        try {
            val bytes = file.openRead().use { it.readBytes() }
            PanicStoreReadResult.Ready(PanicStateCodec.decode(bytes))
        } catch (_: FileNotFoundException) {
            PanicStoreReadResult.Unavailable(PanicStoreFailure.MISSING)
        } catch (_: PanicStateCorruptionException) {
            PanicStoreReadResult.Unavailable(PanicStoreFailure.CORRUPT)
        } catch (_: Throwable) {
            PanicStoreReadResult.Unavailable(PanicStoreFailure.IO_ERROR)
        }

    private fun writeUnlocked(state: PanicPersistentState): PanicStoreFailure? {
        file.baseFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return PanicStoreFailure.COMMIT_FAILED
            }
        }

        val bytes = try {
            PanicStateCodec.encode(state)
        } catch (_: Throwable) {
            return PanicStoreFailure.CORRUPT
        }

        var output: FileOutputStream? = null
        return try {
            output = file.startWrite()
            output.write(bytes)
            file.finishWrite(output)
            null
        } catch (cancelled: CancellationException) {
            output?.let(file::failWrite)
            throw cancelled
        } catch (_: Throwable) {
            output?.let(file::failWrite)
            PanicStoreFailure.COMMIT_FAILED
        }
    }
}
