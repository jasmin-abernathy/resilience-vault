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

sealed interface VaultJournalRead {
    data object Missing : VaultJournalRead
    data class Ready(val record: VaultProvisioningJournal) : VaultJournalRead
    data object Unavailable : VaultJournalRead
}

internal interface VaultProvisioningJournalFile {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}

/**
 * Filesystem-independent verifier for the provisioning journal.
 *
 * Missing is returned only for a genuinely absent file. Corruption, uncertain writes and
 * verification failures permanently latch this store instance closed. Explicit create() is the
 * only path from Missing to BEGIN; there is deliberately no reset/repair API.
 */
internal class VerifiedVaultProvisioningJournalStore(
    private val file: VaultProvisioningJournalFile
) {
    private var failed = false

    @Synchronized
    fun read(): VaultJournalRead {
        if (failed) return VaultJournalRead.Unavailable
        return try {
            val bytes = file.read()
            if (bytes.size != VaultProvisioningJournalCodec.FILE_BYTES) {
                throw IOException("Invalid provisioning journal length")
            }
            VaultJournalRead.Ready(VaultProvisioningJournalCodec.decode(bytes))
        } catch (_: FileNotFoundException) {
            VaultJournalRead.Missing
        } catch (_: Exception) {
            failed = true
            VaultJournalRead.Unavailable
        }
    }

    @Synchronized
    fun create(record: VaultProvisioningJournal) {
        require(record.phase == VaultProvisioningPolicy.JournalPhase.BEGIN)
        check(read() == VaultJournalRead.Missing) { "Provisioning journal is not fresh" }
        commit(record)
    }

    @Synchronized
    fun advance(expected: VaultProvisioningJournal, next: VaultProvisioningJournal) {
        check(read() == VaultJournalRead.Ready(expected)) { "Provisioning journal changed" }
        require(expected.vaultIdHex == next.vaultIdHex &&
            expected.generationHex == next.generationHex && expected.epoch == next.epoch)
        require(next == expected.next(next.phase))
        commit(next)
    }

    private fun commit(record: VaultProvisioningJournal) {
        check(!failed)
        try {
            file.write(VaultProvisioningJournalCodec.encode(record))
            check(read() == VaultJournalRead.Ready(record)) {
                "Provisioning journal verification failed"
            }
        } catch (error: Exception) {
            failed = true
            throw error
        }
    }
}

/**
 * Per-vault-generation, no-backup Android journal.
 *
 * The public API is unchanged; the verifier is split out so crash/write-failure semantics can be
 * tested without pretending JVM tests prove Android AtomicFile durability.
 */
class AtomicVaultProvisioningJournalStore private constructor(
    private val delegate: VerifiedVaultProvisioningJournalStore
) {
    companion object {
        private val instances = mutableMapOf<String, AtomicVaultProvisioningJournalStore>()

        @Synchronized
        fun forVault(
            context: Context,
            identity: VaultProvisioningJournal
        ): AtomicVaultProvisioningJournalStore {
            val name = "${identity.vaultIdHex}.${identity.generationHex}.bin"
            val path = File(context.noBackupFilesDir, "security/provisioning/$name").canonicalFile
            return instances.getOrPut(path.path) {
                AtomicVaultProvisioningJournalStore(
                    VerifiedVaultProvisioningJournalStore(
                        AndroidVaultProvisioningJournalFile(path)
                    )
                )
            }
        }
    }

    fun read(): VaultJournalRead = delegate.read()

    fun create(record: VaultProvisioningJournal) = delegate.create(record)

    fun advance(expected: VaultProvisioningJournal, next: VaultProvisioningJournal) =
        delegate.advance(expected, next)
}

private class AndroidVaultProvisioningJournalFile(
    private val path: File
) : VaultProvisioningJournalFile {
    private val file = AtomicFile(path)

    override fun read(): ByteArray = try {
        file.openRead().use { input ->
            val bounded = ByteArrayOutputStream(VaultProvisioningJournalCodec.FILE_BYTES)
            val buffer = ByteArray(128)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bounded.size() + count > VaultProvisioningJournalCodec.FILE_BYTES) {
                    throw IOException("Provisioning journal too large")
                }
                bounded.write(buffer, 0, count)
            }
            bounded.toByteArray()
        }
    } catch (missing: FileNotFoundException) {
        if (path.exists() || File(path.path + ".new").exists() ||
            File(path.path + ".bak").exists()) {
            throw IOException("Provisioning journal has unresolved AtomicFile state", missing)
        }
        throw missing
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size == VaultProvisioningJournalCodec.FILE_BYTES)
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
                    throw IOException("Provisioning journal parent is not a directory")
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
