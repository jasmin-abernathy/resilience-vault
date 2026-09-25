package org.lepotager.resiliencevault.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lepotager.resiliencevault.panic.AtomicFilePanicStateStore
import org.lepotager.resiliencevault.panic.PanicInitializationResult
import org.lepotager.resiliencevault.panic.PanicStoreFailure
import org.lepotager.resiliencevault.panic.PanicStoreReadResult

internal enum class FirstInstallRefusal {
    PANIC_STATE_UNAVAILABLE,
    SECURITY_FOOTPRINT_PRESENT,
    VAULT_KEK_PRESENT,
    PACKAGE_ALREADY_UPDATED,
    PLATFORM_EVIDENCE_UNAVAILABLE,
}

internal sealed interface FirstInstallSecurityStatus {
    data object Eligible : FirstInstallSecurityStatus
    data object AlreadyInitialized : FirstInstallSecurityStatus
    data class Refused(val reason: FirstInstallRefusal) : FirstInstallSecurityStatus
}

internal sealed interface FirstInstallSecurityAction {
    data object Initialized : FirstInstallSecurityAction
    data object AlreadyInitialized : FirstInstallSecurityAction
    data class Refused(val reason: FirstInstallRefusal) : FirstInstallSecurityAction
}

internal object FirstInstallSecurityPolicy {
    fun evaluate(
        panic: PanicStoreReadResult,
        firstInstallTime: Long,
        lastUpdateTime: Long,
        securityEntries: List<String>,
        aliases: Set<String>,
    ): FirstInstallSecurityStatus {
        if (panic is PanicStoreReadResult.Ready) {
            return FirstInstallSecurityStatus.AlreadyInitialized
        }
        val unavailable = panic as PanicStoreReadResult.Unavailable
        if (unavailable.failure != PanicStoreFailure.MISSING) {
            return FirstInstallSecurityStatus.Refused(FirstInstallRefusal.PANIC_STATE_UNAVAILABLE)
        }
        if (firstInstallTime <= 0L || lastUpdateTime < firstInstallTime) {
            return FirstInstallSecurityStatus.Refused(
                FirstInstallRefusal.PLATFORM_EVIDENCE_UNAVAILABLE
            )
        }
        if (lastUpdateTime > firstInstallTime) {
            return FirstInstallSecurityStatus.Refused(FirstInstallRefusal.PACKAGE_ALREADY_UPDATED)
        }
        if (securityEntries.isNotEmpty()) {
            return FirstInstallSecurityStatus.Refused(FirstInstallRefusal.SECURITY_FOOTPRINT_PRESENT)
        }
        if (aliases.any { it.startsWith(KEK_PREFIX) }) {
            return FirstInstallSecurityStatus.Refused(FirstInstallRefusal.VAULT_KEK_PRESENT)
        }
        return FirstInstallSecurityStatus.Eligible
    }

    private const val KEK_PREFIX = "rv.kek.v1."
}

/**
 * Explicit bootstrap only. It never treats missing state alone as proof of a fresh installation.
 * False negatives are intentional: reinstall/recovery is safer than resetting uncertain state.
 */
internal class AndroidFirstInstallSecurityCeremony(context: Context) {
    private val app = context.applicationContext
    private val panicStore = AtomicFilePanicStateStore.create(app)
    private val kek = AndroidVaultKek(app)
    private val securityDir = File(app.noBackupFilesDir, "security")

    suspend fun inspect(): FirstInstallSecurityStatus {
        val panic = panicStore.read()
        if (panic is PanicStoreReadResult.Ready) {
            return FirstInstallSecurityStatus.AlreadyInitialized
        }
        if (panic is PanicStoreReadResult.Unavailable &&
            panic.failure != PanicStoreFailure.MISSING) {
            return FirstInstallSecurityStatus.Refused(FirstInstallRefusal.PANIC_STATE_UNAVAILABLE)
        }

        val evidence = withContext(Dispatchers.IO) {
            val times = packageTimes() ?: return@withContext null
            val entries = securityEntries() ?: return@withContext null
            val aliases = try {
                kek.aliases()
            } catch (_: Exception) {
                return@withContext null
            }
            Triple(times, entries, aliases)
        } ?: return FirstInstallSecurityStatus.Refused(
            FirstInstallRefusal.PLATFORM_EVIDENCE_UNAVAILABLE
        )

        return FirstInstallSecurityPolicy.evaluate(
            panic = panic,
            firstInstallTime = evidence.first.first,
            lastUpdateTime = evidence.first.second,
            securityEntries = evidence.second,
            aliases = evidence.third,
        )
    }

    suspend fun initialize(): FirstInstallSecurityAction =
        when (val status = inspect()) {
            FirstInstallSecurityStatus.AlreadyInitialized ->
                FirstInstallSecurityAction.AlreadyInitialized
            is FirstInstallSecurityStatus.Refused ->
                FirstInstallSecurityAction.Refused(status.reason)
            FirstInstallSecurityStatus.Eligible -> {
                // Re-evaluate immediately before the only permitted Missing -> IDLE transition.
                when (val rechecked = inspect()) {
                    FirstInstallSecurityStatus.Eligible -> when (val result = panicStore.initializeFresh()) {
                        PanicInitializationResult.Created -> FirstInstallSecurityAction.Initialized
                        PanicInitializationResult.AlreadyInitialized ->
                            FirstInstallSecurityAction.AlreadyInitialized
                        is PanicInitializationResult.Unavailable ->
                            FirstInstallSecurityAction.Refused(
                                FirstInstallRefusal.PANIC_STATE_UNAVAILABLE
                            )
                    }
                    FirstInstallSecurityStatus.AlreadyInitialized ->
                        FirstInstallSecurityAction.AlreadyInitialized
                    is FirstInstallSecurityStatus.Refused ->
                        FirstInstallSecurityAction.Refused(rechecked.reason)
                }
            }
        }

    private fun securityEntries(): List<String>? {
        if (!securityDir.exists()) return emptyList()
        if (!securityDir.isDirectory) return null
        return securityDir.list()?.sorted()
    }

    private fun packageTimes(): Pair<Long, Long>? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageInfo(
                app.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0)
        }
        info.firstInstallTime to info.lastUpdateTime
    } catch (_: Exception) {
        null
    }
}
