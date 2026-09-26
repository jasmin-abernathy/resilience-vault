package org.lepotager.resiliencevault.crypto

import android.content.Context
import org.lepotager.resiliencevault.cloud.AndroidReadCredentialOwner
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
) {
    companion object {
        private val instances = mutableMapOf<String, AndroidVaultLifecycle>()
        @Synchronized fun forInstallation(context: Context): AndroidVaultLifecycle {
            val app = context.applicationContext
            val path = app.noBackupFilesDir.canonicalPath
            return instances[path] ?: AndroidVaultLifecycle(app).also { instances[path] = it }
        }
    }
    private val panicStore = AtomicFilePanicStateStore.create(context)
    private val authorityStore = AtomicActiveVaultAuthorityStore.create(context)
    private val registryStore = AtomicVaultSecurityRegistryStore.create(context)
    private val key = AndroidVaultKek(context)
    private val readCredentials = AndroidReadCredentialOwner(context)
    private val runtime = VaultCryptoRuntime(
        VaultAccessLeaseManagers.forStore(panicStore),
        { VaultAliasDestruction(key::aliases, key::deleteAlias, key::aliasExists).destroyAll() },
        { readCredentials.destroyAll() },
    )
    val panicEffects: LocalCriticalPanicEffects get() = runtime

    suspend fun createExplicit(identity: VaultProvisioningJournal, prompt: AuthenticatedCipherPrompt): VaultLeaseExecution<Unit> =
        runtime.useSession(VaultAccessOperation.RESTORE, {
            check(authorityStore.read() == ActiveVaultAuthorityRead.Missing) {
                "Fresh crypto provisioning requires missing active authority"
            }
            check(AndroidVaultRotationEffects(context, identity, prompt).state() == null)
            check(key.aliases().none { it.startsWith("rv.kek.v1.") }) { "Orphan KEK prevents fresh provisioning" }
            VaultProvisioningTransaction(AndroidVaultProvisioningEffects(context, identity, prompt)).createExplicit(identity)
        }) { Unit }

    /** Block must not retain handles/plaintext or publish after its lease ends. */
    suspend fun <T> useExisting(identity: VaultProvisioningJournal, prompt: AuthenticatedCipherPrompt,
                               operation: VaultAccessOperation, block: suspend (TinkVaultSession) -> T): VaultLeaseExecution<T> =
        runtime.useSession(operation, {
            requireReadyAuthority(identity)
            open(identity, prompt)
        }) { session ->
            requireReadyAuthority(identity)
            block(session)
        }

    suspend fun rotate(identity: VaultProvisioningJournal, prompt: AuthenticatedCipherPrompt): VaultLeaseExecution<Unit> =
        runtime.useSession(VaultAccessOperation.RESTORE, {
            requireBlockedAuthorityForRotation(identity)
            open(identity, prompt)
        }) { session ->
            requireBlockedAuthorityForRotation(identity)
            VaultKekRotation(AndroidVaultRotationEffects(context, identity, prompt)).rotate(session)
        }

    private fun requireReadyAuthority(identity: VaultProvisioningJournal) {
        val resolved = ActiveVaultAuthorityResolver.resolve(
            authorityStore.read(),
            registryStore.read(),
        )
        val record = (resolved as? ActiveVaultAuthorityResolution.Ready)?.record
            ?: error("Active vault authority unavailable")
        check(record.vaultIdHex == identity.vaultIdHex)
        check(record.vaultGenerationHex == identity.generationHex)
    }

    private fun requireBlockedAuthorityForRotation(identity: VaultProvisioningJournal) {
        val record = (authorityStore.read() as? ActiveVaultAuthorityRead.Ready)?.record
            ?: error("Active vault authority unavailable")
        check(record.status == ActiveVaultAuthorityStatus.BLOCKED_TRANSITION)
        check(record.vaultIdHex == identity.vaultIdHex)
        check(record.vaultGenerationHex == identity.generationHex)
        val registry = (registryStore.read() as? VaultSecurityRegistryRead.Ready)?.record
            ?: error("Security registry unavailable")
        check(registry.vaultIdHex == identity.vaultIdHex)
        check(registry.generationHex == identity.generationHex)
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
