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

sealed interface VaultSecurityRegistryRead {
    data object Missing : VaultSecurityRegistryRead
    data class Ready(val record: VaultSecurityRegistryRecord) : VaultSecurityRegistryRead
    data object Unavailable : VaultSecurityRegistryRead
}

internal interface VaultSecurityRegistryFile {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}

/**
 * Review-only durable registry store. No production caller is allowed to treat Missing as proof
 * that a previously known vault never existed. Wiring/migration remains a GPT-6 gate.
 */
internal class VerifiedVaultSecurityRegistryStore(
    private val file: VaultSecurityRegistryFile
) {
    private var failed = false

    @Synchronized
    fun read(): VaultSecurityRegistryRead {
        if (failed) return VaultSecurityRegistryRead.Unavailable
        return try {
            VaultSecurityRegistryRead.Ready(VaultSecurityRegistryCodec.decode(file.read()))
        } catch (_: FileNotFoundException) {
            VaultSecurityRegistryRead.Missing
        } catch (_: Exception) {
            failed = true
            VaultSecurityRegistryRead.Unavailable
        }
    }

    @Synchronized
    fun createFresh(record: VaultSecurityRegistryRecord) {
        require(record.revision == 1L)
        check(read() == VaultSecurityRegistryRead.Missing) { "Security registry is not fresh" }
        commit(record)
    }

    @Synchronized
    fun replaceExpected(
        expected: VaultSecurityRegistryRecord,
        next: VaultSecurityRegistryRecord,
    ) {
        check(read() == VaultSecurityRegistryRead.Ready(expected)) {
            "Security registry changed"
        }
        require(next.vaultIdHex == expected.vaultIdHex)
        require(next.generationHex == expected.generationHex)
        require(next.revision == expected.revision + 1L)
        commit(next)
    }

    private fun commit(record: VaultSecurityRegistryRecord) {
        check(!failed)
        try {
            file.write(VaultSecurityRegistryCodec.encode(record))
            check(read() == VaultSecurityRegistryRead.Ready(record)) {
                "Security registry verification failed"
            }
        } catch (error: Exception) {
            failed = true
            throw error
        }
    }
}

/**
 * V1 candidate keeps one installation-level current-vault registry in noBackupFilesDir.
 * It has no reset/delete method and is not connected to AndroidVaultKek yet.
 */
class AtomicVaultSecurityRegistryStore private constructor(
    internal val delegate: VerifiedVaultSecurityRegistryStore
) {
    companion object {
        private val instances = mutableMapOf<String, AtomicVaultSecurityRegistryStore>()

        @Synchronized
        fun create(context: Context): AtomicVaultSecurityRegistryStore {
            val path = File(
                context.noBackupFilesDir,
                "security/vault-security-registry.bin"
            ).canonicalFile
            return instances.getOrPut(path.path) {
                AtomicVaultSecurityRegistryStore(
                    VerifiedVaultSecurityRegistryStore(AndroidVaultSecurityRegistryFile(path))
                )
            }
        }
    }

    fun read(): VaultSecurityRegistryRead = delegate.read()

    /** Review-only until GPT-6 approves the surrounding provisioning transaction. */
    internal fun createFresh(record: VaultSecurityRegistryRecord) = delegate.createFresh(record)

    /** Review-only until GPT-6 approves rotation/removal ordering and migration. */
    internal fun replaceExpected(
        expected: VaultSecurityRegistryRecord,
        next: VaultSecurityRegistryRecord,
    ) = delegate.replaceExpected(expected, next)
}

private class AndroidVaultSecurityRegistryFile(
    private val path: File
) : VaultSecurityRegistryFile {
    private val file = AtomicFile(path)

    override fun read(): ByteArray = try {
        file.openRead().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > VaultSecurityRegistryCodec.MAX_FILE_BYTES) {
                    throw IOException("Security registry too large")
                }
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    } catch (missing: FileNotFoundException) {
        if (path.exists() || File(path.path + ".new").exists() ||
            File(path.path + ".bak").exists()) {
            throw IOException("Security registry has unresolved AtomicFile state", missing)
        }
        throw missing
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size <= VaultSecurityRegistryCodec.MAX_FILE_BYTES)
        var stream: FileOutputStream? = null
        try {
            path.parentFile!!.mkdirs()
            stream = file.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            stream = null
            val directory = Os.open(path.parent!!, OsConstants.O_RDONLY, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(directory).st_mode)) {
                    throw IOException("Security registry parent is not a directory")
                }
                Os.fsync(directory)
            } finally {
                Os.close(directory)
            }
        } catch (error: Exception) {
            stream?.let {
                try { file.failWrite(it) } catch (_: Exception) { /* original error wins */ }
            }
            throw error
        }
    }
}
