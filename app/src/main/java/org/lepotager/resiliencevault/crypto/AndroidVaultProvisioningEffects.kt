package org.lepotager.resiliencevault.crypto

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/** Concrete storage/key adapter. Keystore errors and uncertain durable writes propagate. */
internal class AndroidVaultProvisioningEffects(
    context: Context,
    private val identity: VaultProvisioningJournal,
    private val prompt: AuthenticatedCipherPrompt,
) : VaultProvisioningEffects {
    private val journalStore = AtomicVaultProvisioningJournalStore.forVault(context, identity)
    private val registryStore = AtomicVaultSecurityRegistryStore.create(context)
    private val kek = AndroidVaultKek(context)
    private val blob = VerifiedEpochEnvelopeFile(File(context.noBackupFilesDir,
        "security/epochs/${identity.vaultIdHex}.${identity.generationHex}.${identity.epoch}.bin"))

    override fun journal() = journalStore.read()
    override fun registry() = registryStore.read()
    override fun envelope() = blob.read()
    override fun keyExists() = kek.exists(identity)
    override fun begin(record: VaultProvisioningJournal) = journalStore.create(record)
    override fun advance(previous: VaultProvisioningJournal, next: VaultProvisioningJournal) = journalStore.advance(previous, next)
    override fun createKey(record: VaultProvisioningJournal) { kek.createExplicitly(journalStore, record) }
    override fun localKek(record: VaultProvisioningJournal): LocalKekEnvelope =
        AuthenticatedLocalKekEnvelope({ kek.loadExisting(journalStore, record) }, prompt)
    override fun writeEnvelope(bytes: ByteArray) = blob.write(bytes)
    override fun publishRegistry(record: VaultSecurityRegistryRecord) = registryStore.createFresh(record)
    override fun alias(record: VaultProvisioningJournal) = kek.aliasFor(record)
}

/** Singleton lock/latch per canonical path, including verification after AtomicFile finishWrite. */
internal class VerifiedEpochEnvelopeFile(
    path: File, private val maxBytes: Int = TinkVaultSession.MAX_KEYSET_BYTES + 152,
) {
    private class Guard { var failed = false }
    companion object {
        private val guards = mutableMapOf<String, Guard>()
        @Synchronized private fun guard(path: String): Guard = guards.getOrPut(path) { Guard() }
    }
    private val path = path.canonicalFile
    private val atomic = AtomicFile(this.path)
    private val guard = guard(this.path.path)

    fun read(): ByteArray? = synchronized(guard) {
        check(!guard.failed) { "Envelope store unavailable" }
        try {
            atomic.openRead().use { input ->
                val bytes = input.readNBytesCompat(maxBytes + 1)
                require(bytes.isNotEmpty() && bytes.size <= maxBytes)
                bytes
            }
        } catch (missing: FileNotFoundException) {
            if (path.exists() || File(path.path + ".new").exists() || File(path.path + ".bak").exists()) {
                guard.failed = true
                throw IOException("Unresolved epoch envelope state")
            }
            null
        } catch (error: Exception) { guard.failed = true; throw error }
    }

    fun write(bytes: ByteArray) = synchronized(guard) {
        check(!guard.failed)
        require(bytes.size in 1..maxBytes)
        var stream: FileOutputStream? = null
        try {
            val parent = checkNotNull(path.parentFile)
            check(parent.isDirectory || parent.mkdirs())
            stream = atomic.startWrite()
            stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream); stream = null
            val fd = Os.open(parent.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
            check(read()?.contentEquals(bytes) == true) { "Envelope write unconfirmed" }
        } catch (error: Exception) {
            guard.failed = true
            stream?.let { try { atomic.failWrite(it) } catch (_: Exception) { } }
            throw error
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < max) {
        val count = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (count < 0) break
        check(count > 0)
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
