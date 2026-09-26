package org.lepotager.resiliencevault.crypto

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

internal interface ActiveVaultAuthorityFile {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}

internal class VerifiedActiveVaultAuthorityStore(
    private val file: ActiveVaultAuthorityFile,
) {
    private var failed = false

    @Synchronized
    fun read(): ActiveVaultAuthorityRead {
        if (failed) return ActiveVaultAuthorityRead.Unavailable
        return try {
            ActiveVaultAuthorityRead.Ready(
                ActiveVaultAuthorityCodec.decode(file.read())
            )
        } catch (_: FileNotFoundException) {
            ActiveVaultAuthorityRead.Missing
        } catch (_: Exception) {
            failed = true
            ActiveVaultAuthorityRead.Unavailable
        }
    }

    @Synchronized
    fun createFresh(record: ActiveVaultAuthorityRecord) {
        require(record.authorityRevision == 1L)
        require(record.status == ActiveVaultAuthorityStatus.READY)
        require(
            record.mode == org.lepotager.resiliencevault.panic.RemoteDeleteConfiguration.UNKNOWN ||
                record.mode == org.lepotager.resiliencevault.panic.RemoteDeleteConfiguration.NOT_CONFIGURED
        ) { "CONFIGURED publication requires backend transaction and is unavailable" }
        check(read() == ActiveVaultAuthorityRead.Missing) { "Active authority is not fresh" }
        commit(record)
    }

    @Synchronized
    fun replaceExpected(
        expected: ActiveVaultAuthorityRecord,
        next: ActiveVaultAuthorityRecord,
    ) {
        check(read() == ActiveVaultAuthorityRead.Ready(expected)) {
            "Active authority changed"
        }
        require(next.vaultIdHex == expected.vaultIdHex)
        require(next.vaultGenerationHex == expected.vaultGenerationHex)
        require(next.authorityRevision == expected.authorityRevision + 1L)
        require(next.mode != org.lepotager.resiliencevault.panic.RemoteDeleteConfiguration.CONFIGURED) {
            "CONFIGURED publication requires backend transaction and is unavailable"
        }
        commit(next)
    }

    private fun commit(record: ActiveVaultAuthorityRecord) {
        check(!failed)
        try {
            val bytes = ActiveVaultAuthorityCodec.encode(record)
            file.write(bytes)
            check(read() == ActiveVaultAuthorityRead.Ready(record)) {
                "Active authority verification failed"
            }
        } catch (error: Exception) {
            failed = true
            throw error
        }
    }
}

class AtomicActiveVaultAuthorityStore private constructor(
    internal val delegate: VerifiedActiveVaultAuthorityStore,
) {
    companion object {
        private val instances = mutableMapOf<String, AtomicActiveVaultAuthorityStore>()

        @Synchronized
        fun create(context: Context): AtomicActiveVaultAuthorityStore {
            val path = File(
                context.noBackupFilesDir,
                "security/active-vault-authority.bin",
            ).canonicalFile
            return instances.getOrPut(path.path) {
                AtomicActiveVaultAuthorityStore(
                    VerifiedActiveVaultAuthorityStore(
                        AndroidActiveVaultAuthorityFile(path)
                    )
                )
            }
        }
    }

    fun read(): ActiveVaultAuthorityRead = delegate.read()

    internal fun createFresh(record: ActiveVaultAuthorityRecord) =
        delegate.createFresh(record)

    internal fun replaceExpected(
        expected: ActiveVaultAuthorityRecord,
        next: ActiveVaultAuthorityRecord,
    ) = delegate.replaceExpected(expected, next)
}

private class AndroidActiveVaultAuthorityFile(
    private val path: File,
) : ActiveVaultAuthorityFile {
    private val atomic = AtomicFile(path)

    override fun read(): ByteArray = try {
        atomic.openRead().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > ActiveVaultAuthorityCodec.MAX_FILE_BYTES) {
                    throw IOException("Active authority too large")
                }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    } catch (missing: FileNotFoundException) {
        if (
            path.exists() ||
            File(path.path + ".new").exists() ||
            File(path.path + ".bak").exists()
        ) {
            throw IOException("Active authority has unresolved AtomicFile state", missing)
        }
        throw missing
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size <= ActiveVaultAuthorityCodec.MAX_FILE_BYTES)
        var stream: FileOutputStream? = null
        try {
            path.parentFile!!.mkdirs()
            stream = atomic.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
            val directory = Os.open(path.parent!!, OsConstants.O_RDONLY, 0)
            try {
                check(OsConstants.S_ISDIR(Os.fstat(directory).st_mode))
                Os.fsync(directory)
            } finally {
                Os.close(directory)
            }
        } catch (error: Exception) {
            stream?.let {
                try {
                    atomic.failWrite(it)
                } catch (_: Exception) {
                    // Preserve the original failure.
                }
            }
            throw error
        }
    }
}
