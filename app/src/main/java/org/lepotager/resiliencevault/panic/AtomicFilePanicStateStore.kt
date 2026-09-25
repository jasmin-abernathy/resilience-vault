package org.lepotager.resiliencevault.panic

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Single-process store: every caller for one path shares the same mutex/failure latch.
 * No first-launch initialization here: a missing security record must never reopen a vault.
 * Device kill/power-loss tests are still required before activating destructive effects.
 */
class AtomicFilePanicStateStore private constructor(
    private val verified: VerifiedPanicStateStore
) : PanicStateStore by verified {
    internal suspend fun initializeFresh(): PanicInitializationResult = verified.initializeFresh()
    companion object {
        private val instances = mutableMapOf<String, AtomicFilePanicStateStore>()

        @Synchronized
        fun create(
            context: Context,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): AtomicFilePanicStateStore {
            val path = File(context.noBackupFilesDir, "security/remote-panic-state.bin").canonicalFile
            return instances.getOrPut(path.path) {
                AtomicFilePanicStateStore(VerifiedPanicStateStore(AndroidStateFile(path), ioDispatcher))
            }
        }
    }
}

private class AndroidStateFile(private val path: File) : PanicStateFile {
    private val file = AtomicFile(path)

    override fun read(): ByteArray = file.openRead().use { input ->
        // Bounded before allocation, even for a corrupt/oversized file.
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > PanicStateCodec.MAX_FILE_BYTES) {
                throw IOException("State exceeds size limit")
            }
            bytes.write(buffer, 0, count)
        }
        bytes.toByteArray()
    }

    override fun write(bytes: ByteArray) {
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            // Explicitly propagate sync errors; AtomicFile finish has no result value.
            output.fd.sync()
            file.finishWrite(output)
            output = null
            val directory = Os.open(path.parent!!, OsConstants.O_RDONLY, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(directory).st_mode)) {
                    throw IOException("State parent is not a directory")
                }
                Os.fsync(directory)
            } finally {
                Os.close(directory)
            }
            // VerifiedPanicStateStore subsequently compares the committed bytes.
        } catch (error: Exception) {
            output?.let {
                try { file.failWrite(it) } catch (_: Exception) { /* original error wins */ }
            }
            throw error
        }
    }
}
