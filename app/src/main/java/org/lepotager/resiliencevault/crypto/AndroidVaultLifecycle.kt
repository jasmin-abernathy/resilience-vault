package org.lepotager.resiliencevault.crypto

import android.content.Context
import org.lepotager.resiliencevault.panic.AtomicFilePanicStateStore
import org.lepotager.resiliencevault.panic.LocalCriticalPanicEffects
import org.lepotager.resiliencevault.panic.VaultAccessLeaseManagers
import org.lepotager.resiliencevault.panic.VaultAccessOperation
import org.lepotager.resiliencevault.panic.VaultLeaseExecution

/** Internal assembly; production/SMS gates remain closed. All crypto callers MUST use this owner.
 * Single process only. Admission requires an already initialized, readable IDLE panic record.
 * The credential remover must remove read credentials, preserving only a separate DELETE capsule.
 */
internal class AndroidVaultLifecycle private constructor(
    private val context: Context,
    private val removeReadCredentials: () -> Unit,
) {
    companion object {
        private val instances = mutableMapOf<String, AndroidVaultLifecycle>()
        @Synchronized fun forInstallation(context: Context, removeReadCredentials: () -> Unit): AndroidVaultLifecycle {
            val app = context.applicationContext
            val path = app.noBackupFilesDir.canonicalPath
            return instances[path]?.also {
                check(it.removeReadCredentials === removeReadCredentials) { "Conflicting credential owner" }
            } ?: AndroidVaultLifecycle(app, removeReadCredentials).also { instances[path] = it }
        }
    }
    private val key = AndroidVaultKek(context)
    private val runtime = VaultCryptoRuntime(
        VaultAccessLeaseManagers.forStore(AtomicFilePanicStateStore.create(context)),
        { VaultAliasDestruction(key::aliases, key::deleteAlias, key::aliasExists).destroyAll() },
        removeReadCredentials,
    )
    val panicEffects: LocalCriticalPanicEffects get() = runtime

    suspend fun createExplicit(identity: VaultProvisioningJournal, auth: PerUseCipherAuthorization): VaultLeaseExecution<Unit> =
        runtime.useSession(VaultAccessOperation.RESTORE, {
            check(AndroidVaultRotationEffects(context, identity, auth).state() == null)
            check(key.aliases().none { it.startsWith("rv.kek.v1.") }) { "Orphan KEK prevents fresh provisioning" }
            VaultProvisioningTransaction(AndroidVaultProvisioningEffects(context, identity, auth)).createExplicit(identity)
        }) { Unit }

    /** Block must not retain handles/plaintext or publish after its lease ends. */
    suspend fun <T> useExisting(identity: VaultProvisioningJournal, auth: PerUseCipherAuthorization,
                               operation: VaultAccessOperation, block: suspend (TinkVaultSession) -> T): VaultLeaseExecution<T> =
        runtime.useSession(operation, { open(identity, auth) }, block)

    suspend fun rotate(identity: VaultProvisioningJournal, auth: PerUseCipherAuthorization): VaultLeaseExecution<Unit> =
        runtime.useSession(VaultAccessOperation.RESTORE, { open(identity, auth) }) { session ->
            VaultKekRotation(AndroidVaultRotationEffects(context, identity, auth)).rotate(session)
        }

    private fun open(identity: VaultProvisioningJournal, auth: PerUseCipherAuthorization): TinkVaultSession {
        require(identity.phase == VaultProvisioningPolicy.JournalPhase.COMMITTED)
        check(AtomicVaultProvisioningJournalStore.forVault(context, identity).read() == VaultJournalRead.Ready(identity))
        val rotation = AndroidVaultRotationEffects(context, identity, auth)
        return if (rotation.state() == null) {
            VaultProvisioningTransaction(AndroidVaultProvisioningEffects(context, identity, auth)).openExisting(identity)
        } else {
            VaultKekRotation(rotation).openExisting(identity.vaultIdHex, identity.generationHex, identity.epoch)
        }
    }
}
