package org.lepotager.resiliencevault.panic

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lepotager.resiliencevault.crypto.AndroidVaultKek

internal enum class FirstInstallSecurityEvidence {
    PRISTINE,
    FILES_PRESENT,
    LOCAL_KEK_PRESENT,
    UNAVAILABLE,
}

internal sealed interface FirstInstallPanicCeremonyResult {
    data object Created : FirstInstallPanicCeremonyResult
    data object AlreadyInitialized : FirstInstallPanicCeremonyResult
    data class Refused(val evidence: FirstInstallSecurityEvidence) :
        FirstInstallPanicCeremonyResult
    data class StoreUnavailable(val failure: PanicStoreFailure) :
        FirstInstallPanicCeremonyResult
}

/**
 * Explicit first-install gate. A missing panic record is never sufficient proof by itself:
 * every file below noBackupFilesDir and every Resilience Vault KEK alias must also be absent.
 */
internal class AndroidFirstInstallPanicCeremony private constructor(
    private val context: Context,
) {
    companion object {
        private val instances = mutableMapOf<String, AndroidFirstInstallPanicCeremony>()

        @Synchronized
        fun create(context: Context): AndroidFirstInstallPanicCeremony {
            val app = context.applicationContext
            val path = app.noBackupFilesDir.canonicalPath
            return instances[path]
                ?: AndroidFirstInstallPanicCeremony(app).also { instances[path] = it }
        }
    }

    private val mutex = Mutex()
    private val store = AtomicFilePanicStateStore.create(context)
    private val kek = AndroidVaultKek(context)

    suspend fun initialize(): FirstInstallPanicCeremonyResult = mutex.withLock {
        when (val current = store.read()) {
            is PanicStoreReadResult.Ready ->
                return@withLock FirstInstallPanicCeremonyResult.AlreadyInitialized
            is PanicStoreReadResult.Unavailable -> {
                if (current.failure != PanicStoreFailure.MISSING) {
                    return@withLock FirstInstallPanicCeremonyResult.StoreUnavailable(
                        current.failure
                    )
                }
            }
        }

        val evidence = withContext(Dispatchers.IO) {
            try {
                FirstInstallSecurityInspector.inspect(
                    context.noBackupFilesDir,
                    kek.aliases(),
                )
            } catch (_: Exception) {
                FirstInstallSecurityEvidence.UNAVAILABLE
            }
        }
        if (evidence != FirstInstallSecurityEvidence.PRISTINE) {
            return@withLock FirstInstallPanicCeremonyResult.Refused(evidence)
        }

        when (val initialized = store.initializeFresh()) {
            PanicInitializationResult.Created ->
                FirstInstallPanicCeremonyResult.Created
            PanicInitializationResult.AlreadyInitialized ->
                FirstInstallPanicCeremonyResult.AlreadyInitialized
            is PanicInitializationResult.Unavailable ->
                FirstInstallPanicCeremonyResult.StoreUnavailable(initialized.failure)
        }
    }
}

internal object FirstInstallSecurityInspector {
    private const val KEK_PREFIX = "rv.kek.v1."

    fun inspect(
        noBackupRoot: File,
        aliases: Set<String>,
    ): FirstInstallSecurityEvidence {
        if (aliases.any { it.startsWith(KEK_PREFIX) }) {
            return FirstInstallSecurityEvidence.LOCAL_KEK_PRESENT
        }

        val root = try {
            noBackupRoot.canonicalFile
        } catch (_: Exception) {
            return FirstInstallSecurityEvidence.UNAVAILABLE
        }
        if (!root.exists() || !root.isDirectory) {
            return FirstInstallSecurityEvidence.UNAVAILABLE
        }

        val rootPrefix = root.path + File.separator
        val pending = ArrayDeque<File>()
        val visitedDirectories = mutableSetOf(root.path)
        pending.add(root)

        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val children = try {
                directory.listFiles()
            } catch (_: Exception) {
                return FirstInstallSecurityEvidence.UNAVAILABLE
            } ?: return FirstInstallSecurityEvidence.UNAVAILABLE

            for (child in children) {
                val canonical = try {
                    child.canonicalFile
                } catch (_: Exception) {
                    return FirstInstallSecurityEvidence.UNAVAILABLE
                }
                if (!canonical.path.startsWith(rootPrefix)) {
                    return FirstInstallSecurityEvidence.UNAVAILABLE
                }
                if (child.isDirectory) {
                    if (!visitedDirectories.add(canonical.path)) {
                        return FirstInstallSecurityEvidence.UNAVAILABLE
                    }
                    pending.add(child)
                } else {
                    return FirstInstallSecurityEvidence.FILES_PRESENT
                }
            }
        }

        return FirstInstallSecurityEvidence.PRISTINE
    }
}
