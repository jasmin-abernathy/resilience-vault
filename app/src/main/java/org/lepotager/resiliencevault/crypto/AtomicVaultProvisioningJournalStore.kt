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

/**
 * Per-vault-generation, no-backup journal. A process crash during a write is recovered by AtomicFile.
 * Caller must hold the vault's provisioning lock and verify Keystore/inventory evidence before
 * calling create; no key may be created before BEGIN has been committed.
 *
 * This store deliberately has no reset or implicit recovery method.
 */
class AtomicVaultProvisioningJournalStore private constructor(private val path: File) {
    companion object {
        private val instances = mutableMapOf<String, AtomicVaultProvisioningJournalStore>()

        @Synchronized
        fun forVault(
            context: Context,
            identity: VaultProvisioningJournal
        ): AtomicVaultProvisioningJournalStore {
            // VaultProvisioningJournal validates both identifiers before they enter a path.
            val name = "${identity.vaultIdHex}.${identity.generationHex}.bin"
            val path = File(context.noBackupFilesDir, "security/provisioning/$name").canonicalFile
            return instances.getOrPut(path.path) { AtomicVaultProvisioningJournalStore(path) }
        }
    }

    private val file = AtomicFile(path)
    private var failed = false

    @Synchronized
    fun read(): VaultJournalRead {
        if (failed) return VaultJournalRead.Unavailable
        return try {
            val bytes = file.openRead().use { input ->
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
                if (bounded.size() != VaultProvisioningJournalCodec.FILE_BYTES) {
                    throw IOException("Invalid provisioning journal length")
                }
                bounded.toByteArray()
            }
            VaultJournalRead.Ready(VaultProvisioningJournalCodec.decode(bytes))
        } catch (_: FileNotFoundException) {
            if (path.exists() || File(path.path + ".new").exists() ||
                File(path.path + ".bak").exists()) {
                failed = true
                VaultJournalRead.Unavailable
            } else {
                VaultJournalRead.Missing
            }
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
        var stream: FileOutputStream? = null
        try {
            path.parentFile!!.mkdirs()
            stream = file.startWrite()
            stream.write(VaultProvisioningJournalCodec.encode(record))
            stream.fd.sync()
            file.finishWrite(stream)
            stream = null
            val directory = Os.open(path.parent!!, OsConstants.O_RDONLY, 0)
            try {
                check(OsConstants.S_ISDIR(Os.fstat(directory).st_mode))
                Os.fsync(directory)
            } finally {
                Os.close(directory)
            }
            check(read() == VaultJournalRead.Ready(record)) { "Provisioning journal verification failed" }
        } catch (error: Exception) {
            stream?.let {
                try { file.failWrite(it) } catch (_: Exception) { /* preserve original error */ }
            }
            failed = true
            throw error
        }
    }
}
