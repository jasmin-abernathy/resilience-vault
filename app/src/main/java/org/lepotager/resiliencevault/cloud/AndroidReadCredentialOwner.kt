package org.lepotager.resiliencevault.cloud

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore

/**
 * Reserved local namespaces. Panic may destroy READ state, but DELETE-only state is deliberately
 * outside that namespace so a post-panic remote-delete retry can survive.
 */
internal object CredentialNamespaces {
    const val READ_ALIAS_PREFIX = "rv.read-credential.v1."
    const val DELETE_ONLY_ALIAS_PREFIX = "rv.delete-only.v1."

    fun readDirectory(context: Context): File =
        File(context.noBackupFilesDir, "credentials/read")

    fun deleteOnlyDirectory(context: Context): File =
        File(context.noBackupFilesDir, "credentials/delete-only")
}

internal interface CredentialAliasStore {
    fun aliases(): Set<String>
    fun delete(alias: String)
    fun exists(alias: String): Boolean
}

internal interface ReadCredentialFileStore {
    fun destroyAll()
    fun hasResidualState(): Boolean
}

/**
 * Idempotent local panic effect. Errors remain failures: absence is rechecked after every delete.
 */
internal class ReadCredentialDestructor(
    private val aliases: CredentialAliasStore,
    private val files: ReadCredentialFileStore,
) {
    fun destroyAll() {
        val targets = aliases.aliases()
            .filter { it.startsWith(CredentialNamespaces.READ_ALIAS_PREFIX) }

        targets.forEach { alias ->
            aliases.delete(alias)
            check(!aliases.exists(alias)) { "Read credential alias deletion unconfirmed" }
        }

        check(
            aliases.aliases().none {
                it.startsWith(CredentialNamespaces.READ_ALIAS_PREFIX)
            }
        ) { "Read credential aliases remain" }

        files.destroyAll()
        check(!files.hasResidualState()) { "Read credential files remain" }
    }
}

/**
 * Concrete Android owner for local account/read/upload credentials.
 *
 * This class intentionally has no API for the DELETE-only capsule. Future credential writers must
 * use the READ namespace above; the DELETE-only namespace is a separate security authority.
 */
internal class AndroidReadCredentialOwner(context: Context) {
    private val app = context.applicationContext
    private val destructor = ReadCredentialDestructor(
        AndroidCredentialAliasStore(),
        AndroidReadCredentialFileStore(
            CredentialNamespaces.readDirectory(app),
            CredentialNamespaces.deleteOnlyDirectory(app),
        ),
    )

    fun destroyAll() = destructor.destroyAll()
}

private class AndroidCredentialAliasStore : CredentialAliasStore {
    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun aliases(): Set<String> =
        keyStore().aliases().toList().toSet()

    override fun delete(alias: String) {
        require(alias.startsWith(CredentialNamespaces.READ_ALIAS_PREFIX)) {
            "Refusing to delete outside read-credential namespace"
        }
        val store = keyStore()
        if (store.containsAlias(alias)) {
            store.deleteEntry(alias)
        }
        check(!keyStore().containsAlias(alias)) {
            "Read credential alias deletion unconfirmed"
        }
    }

    override fun exists(alias: String): Boolean =
        keyStore().containsAlias(alias)
}

private class AndroidReadCredentialFileStore(
    readDirectory: File,
    deleteOnlyDirectory: File,
) : ReadCredentialFileStore {
    private val readRoot = readDirectory.absoluteFile
    private val deleteOnlyRoot = deleteOnlyDirectory.absoluteFile

    init {
        val readParent = checkNotNull(readRoot.parentFile).canonicalFile
        val deleteParent = checkNotNull(deleteOnlyRoot.parentFile).canonicalFile
        check(readParent == deleteParent) { "Credential namespaces must be siblings" }
        check(readRoot.name == "read" && deleteOnlyRoot.name == "delete-only")
    }

    override fun destroyAll() {
        if (!readRoot.exists()) return
        deleteWithoutFollowingSymlinks(readRoot)
        check(!readRoot.exists()) { "Read credential directory deletion unconfirmed" }
        fsyncDirectory(checkNotNull(readRoot.parentFile))
    }

    override fun hasResidualState(): Boolean {
        if (!readRoot.exists()) return false
        if (!readRoot.isDirectory || Files.isSymbolicLink(readRoot.toPath())) return true
        val children = readRoot.listFiles()
            ?: throw IOException("Cannot inspect read credential directory")
        return children.isNotEmpty()
    }

    private fun deleteWithoutFollowingSymlinks(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            if (!file.delete() && file.exists()) {
                throw IOException("Cannot delete read credential symlink")
            }
            return
        }

        if (file.isDirectory) {
            val children = file.listFiles()
                ?: throw IOException("Cannot list read credential directory")
            children.forEach(::deleteWithoutFollowingSymlinks)
        }

        if (!file.delete() && file.exists()) {
            throw IOException("Cannot delete read credential state")
        }
    }

    private fun fsyncDirectory(directory: File) {
        if (!directory.exists()) return
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) {
                throw IOException("Credential parent is not a directory")
            }
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }
}
