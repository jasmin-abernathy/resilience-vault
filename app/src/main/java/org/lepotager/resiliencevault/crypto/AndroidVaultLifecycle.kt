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
    private val panicStore = AtomicFilePanicStateStore.create(context)
    private val key = AndroidVaultKek(context)
    private val runtime = VaultCryptoRuntime(
        VaultAccessLeaseManagers.forStore(panicStore),
        { VaultAliasDestruction(key::aliases, key::deleteAlias, key::aliasExists).destroyAll() },
        removeReadCredentials,
    )
    val panicEffects: LocalCriticalPanicEffects get() = runtime

    /** Explicit first-install ceremony. Call only from a product flow that has established
     * this is a new installation. Existing/corrupt/unreadable state is never reset.
     */
    suspend fun initializeFirstInstallPanicState() = panicStore.initializeFresh()

    suspend fun createExplicit(identity: VaultProvisioningJournal, prompt: AuthenticatedCipherPrompt): VaultLeaseExecution<Unit> =
        runtime.useSession(VaultAccessOperation.RESTORE, {
            check(AndroidVaultRotationEffects(context, identity, prompt).state() == null)
            check(key.aliases().none { it.startsWith("rv.kek.v1.") }) { "Orphan KEK prevents fresh provisioning" }
            VaultProvisioningTransaction(AndroidVaultProvisioningEffects(context, identity, prompt)).createExplicit(identity)
        }) { Unit }

    /** Block must not retain handles/plaintext or publish after its lease ends. */
    suspend fun <T> useExisting(identity: VaultProvisioningJournal, prompt: AuthenticatedCipherPrompt,
                               operation: VaultAccessOperation, block: suspend (TinkVaultSession) -> T): VaultLeaseExecution<T> =
        runtime.useSession(operation, { open(identity, prompt) }, block)

    suspend fun rotate(identity: VaultProvisioningJournal, prompt: AuthenticatedCipherPrompt): VaultLeaseExecution<Unit> =
        runtime.useSession(VaultAccessOperation.RESTORE, { open(identity, prompt) }) { session ->
            VaultKekRotation(AndroidVaultRotationEffects(context, identity, prompt)).rotate(session)
        }

    private suspend fun open(identity: VaultProvisioningJournal, prompt: AuthenticatedCipherPrompt): TinkVaultSession {
        require(identity.phase == VaultProvisioningPolicy.JournalPhase.COMMITTED)
        check(AtomicVaultProvisioningJournalStore.forVault(context, identity).read() == VaultJournalRead.Ready(identity))
        val rotation = AndroidVaultRotationEffects(context, identity, prompt)
        return if (rotation.state() == null) {
            VaultProvisioningTransaction(AndroidVaultProvisioningEffects(context, identity, prompt)).openExisting(identity)
        } else {
            VaultKekRotation(rotation).openExisting(identity.vaultIdHex, identity.generationHex, identity.epoch)
        }
    }
}
