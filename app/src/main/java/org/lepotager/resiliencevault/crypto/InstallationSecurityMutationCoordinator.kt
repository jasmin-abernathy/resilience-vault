package org.lepotager.resiliencevault.crypto

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lepotager.resiliencevault.panic.AdmissionAuthoritySnapshot
import org.lepotager.resiliencevault.panic.AdmissionResult
import org.lepotager.resiliencevault.panic.ArmRejectionReason
import org.lepotager.resiliencevault.panic.AtomicFilePanicStateStore
import org.lepotager.resiliencevault.panic.PanicAdmissionService
import org.lepotager.resiliencevault.panic.PanicPhase
import org.lepotager.resiliencevault.panic.PanicStoreReadResult
import org.lepotager.resiliencevault.panic.RemoteArmRequest
import org.lepotager.resiliencevault.panic.RemoteArmResult
import org.lepotager.resiliencevault.panic.RemoteDeleteConfiguration
import org.lepotager.resiliencevault.panic.ValidatedSmsEnvelope
import org.lepotager.resiliencevault.panic.VaultAccessBlockReason
import org.lepotager.resiliencevault.panic.VaultLeaseExecution

internal enum class InstallationMutationBlockReason {
    FIRST_INSTALL_NOT_READY,
    PANIC_NOT_IDLE,
    AUTHORITY_UNAVAILABLE,
    AUTHORITY_ALREADY_PRESENT,
    CRYPTO_IDENTITY_UNAVAILABLE,
    CRYPTO_IDENTITY_MISMATCH,
    ROTATION_NOT_STABLE,
    FINALIZATION_FAILED,
}

internal sealed interface InstallationMutationResult {
    data object Completed : InstallationMutationResult
    data class VaultBlocked(val reason: VaultAccessBlockReason) : InstallationMutationResult
    data class Blocked(val reason: InstallationMutationBlockReason) : InstallationMutationResult
}

internal class AndroidInstallationSecurityMutationCoordinator private constructor(
    private val context: Context,
) {
    companion object {
        private val instances = mutableMapOf<String, AndroidInstallationSecurityMutationCoordinator>()

        @Synchronized
        fun create(context: Context): AndroidInstallationSecurityMutationCoordinator {
            val app = context.applicationContext
            val path = app.noBackupFilesDir.canonicalPath
            return instances[path] ?: AndroidInstallationSecurityMutationCoordinator(app)
                .also { instances[path] = it }
        }
    }

    private val mutex = Mutex()
    private val authorityStore = AtomicActiveVaultAuthorityStore.create(context)
    private val registryStore = AtomicVaultSecurityRegistryStore.create(context)
    private val panicStore = AtomicFilePanicStateStore.create(context)
    private val lifecycle = AndroidVaultLifecycle.forInstallation(context)
    private val key = AndroidVaultKek(context)
    private val firstInstall = AndroidFirstInstallSecurityCeremony.create(context)

    suspend fun resolveAuthority(): ActiveVaultAuthorityResolution = mutex.withLock {
        resolveLocked()
    }

    suspend fun adoptExistingUnknown(
        identity: VaultProvisioningJournal,
    ): InstallationMutationResult = mutex.withLock {
        if (firstInstall.status() != FirstInstallSecurityStatus.Ready) {
            return@withLock InstallationMutationResult.Blocked(
                InstallationMutationBlockReason.FIRST_INSTALL_NOT_READY
            )
        }
        if (!panicIdleAndUnarmed()) {
            return@withLock InstallationMutationResult.Blocked(
                InstallationMutationBlockReason.PANIC_NOT_IDLE
            )
        }
        if (authorityStore.read() != ActiveVaultAuthorityRead.Missing) {
            return@withLock InstallationMutationResult.Blocked(
                InstallationMutationBlockReason.AUTHORITY_ALREADY_PRESENT
            )
        }
        if (!committedIdentityIsCoherent(identity)) {
            return@withLock InstallationMutationResult.Blocked(
                InstallationMutationBlockReason.CRYPTO_IDENTITY_UNAVAILABLE
            )
        }
        authorityStore.createFresh(
            ActiveVaultAuthorityRecord.adoptedUnknown(
                identity.vaultIdHex,
                identity.generationHex,
            )
        )
        when (resolveLocked()) {
            is ActiveVaultAuthorityResolution.Ready -> InstallationMutationResult.Completed
            else -> InstallationMutationResult.Blocked(
                InstallationMutationBlockReason.FINALIZATION_FAILED
            )
        }
    }

    suspend fun createFresh(
        begin: VaultProvisioningJournal,
        prompt: AuthenticatedCipherPrompt,
    ): InstallationMutationResult {
        require(begin.phase == VaultProvisioningPolicy.JournalPhase.BEGIN)

        val preflight = mutex.withLock {
            when {
                firstInstall.status() != FirstInstallSecurityStatus.Ready ->
                    InstallationMutationBlockReason.FIRST_INSTALL_NOT_READY
                !panicIdleAndUnarmed() -> InstallationMutationBlockReason.PANIC_NOT_IDLE
                authorityStore.read() != ActiveVaultAuthorityRead.Missing ->
                    InstallationMutationBlockReason.AUTHORITY_ALREADY_PRESENT
                registryStore.read() != VaultSecurityRegistryRead.Missing ->
                    InstallationMutationBlockReason.CRYPTO_IDENTITY_UNAVAILABLE
                AtomicVaultProvisioningJournalStore.forVault(context, begin).read() !=
                    VaultJournalRead.Missing ->
                    InstallationMutationBlockReason.CRYPTO_IDENTITY_UNAVAILABLE
                key.aliases().any { it.startsWith("rv.kek.v1.") } ->
                    InstallationMutationBlockReason.CRYPTO_IDENTITY_UNAVAILABLE
                else -> null
            }
        }
        if (preflight != null) return InstallationMutationResult.Blocked(preflight)

        val created = lifecycle.createExplicit(begin, prompt)
        if (created is VaultLeaseExecution.Blocked) {
            return InstallationMutationResult.VaultBlocked(created.reason)
        }

        return mutex.withLock {
            if (!panicIdleAndUnarmed()) {
                return@withLock InstallationMutationResult.Blocked(
                    InstallationMutationBlockReason.PANIC_NOT_IDLE
                )
            }
            if (authorityStore.read() != ActiveVaultAuthorityRead.Missing) {
                return@withLock InstallationMutationResult.Blocked(
                    InstallationMutationBlockReason.AUTHORITY_ALREADY_PRESENT
                )
            }
            val committed = committedJournalFor(begin)
                ?: return@withLock InstallationMutationResult.Blocked(
                    InstallationMutationBlockReason.CRYPTO_IDENTITY_UNAVAILABLE
                )
            if (!committedIdentityIsCoherent(committed)) {
                return@withLock InstallationMutationResult.Blocked(
                    InstallationMutationBlockReason.CRYPTO_IDENTITY_MISMATCH
                )
            }
            authorityStore.createFresh(
                ActiveVaultAuthorityRecord.freshNotConfigured(
                    begin.vaultIdHex,
                    begin.generationHex,
                )
            )
            when (resolveLocked()) {
                is ActiveVaultAuthorityResolution.Ready -> InstallationMutationResult.Completed
                else -> InstallationMutationResult.Blocked(
                    InstallationMutationBlockReason.FINALIZATION_FAILED
                )
            }
        }
    }

    suspend fun rotate(
        identity: VaultProvisioningJournal,
        prompt: AuthenticatedCipherPrompt,
    ): InstallationMutationResult {
        require(identity.phase == VaultProvisioningPolicy.JournalPhase.COMMITTED)

        val transition = mutex.withLock {
            if (!panicIdleAndUnarmed()) {
                return@withLock null to InstallationMutationBlockReason.PANIC_NOT_IDLE
            }
            val ready = (resolveLocked() as? ActiveVaultAuthorityResolution.Ready)?.record
                ?: return@withLock null to InstallationMutationBlockReason.AUTHORITY_UNAVAILABLE
            if (
                ready.vaultIdHex != identity.vaultIdHex ||
                ready.vaultGenerationHex != identity.generationHex
            ) {
                return@withLock null to InstallationMutationBlockReason.CRYPTO_IDENTITY_MISMATCH
            }
            if (!committedIdentityIsCoherent(identity)) {
                return@withLock null to InstallationMutationBlockReason.CRYPTO_IDENTITY_UNAVAILABLE
            }
            val blocked = ready.copy(
                authorityRevision = ready.authorityRevision + 1,
                status = ActiveVaultAuthorityStatus.BLOCKED_TRANSITION,
            )
            authorityStore.replaceExpected(ready, blocked)
            blocked to null
        }
        val blocked = transition.first
            ?: return InstallationMutationResult.Blocked(checkNotNull(transition.second))

        try {
            val rotated = lifecycle.rotate(identity, prompt)
            if (rotated is VaultLeaseExecution.Blocked) {
                restoreReadyIfUnchanged(blocked, identity)
                return InstallationMutationResult.VaultBlocked(rotated.reason)
            }

            return mutex.withLock {
                if (!panicIdleAndUnarmed()) {
                    return@withLock InstallationMutationResult.Blocked(
                        InstallationMutationBlockReason.PANIC_NOT_IDLE
                    )
                }
                if (!rotationIsStable(identity) || !registryMatches(identity)) {
                    return@withLock InstallationMutationResult.Blocked(
                        InstallationMutationBlockReason.ROTATION_NOT_STABLE
                    )
                }
                val current = authorityStore.read()
                if (current != ActiveVaultAuthorityRead.Ready(blocked)) {
                    return@withLock InstallationMutationResult.Blocked(
                        InstallationMutationBlockReason.AUTHORITY_UNAVAILABLE
                    )
                }
                val ready = blocked.copy(
                    authorityRevision = blocked.authorityRevision + 1,
                    status = ActiveVaultAuthorityStatus.READY,
                )
                authorityStore.replaceExpected(blocked, ready)
                if (resolveLocked() is ActiveVaultAuthorityResolution.Ready) {
                    InstallationMutationResult.Completed
                } else {
                    InstallationMutationResult.Blocked(
                        InstallationMutationBlockReason.FINALIZATION_FAILED
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { restoreReadyIfUnchanged(blocked, identity) }
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable) { restoreReadyIfUnchanged(blocked, identity) }
            throw error
        }
    }

    suspend fun armRemote(
        admission: PanicAdmissionService,
        request: RemoteArmRequest,
    ): RemoteArmResult = mutex.withLock {
        val snapshot = notConfiguredSnapshotOrNull()
            ?: return@withLock RemoteArmResult.Rejected(
                ArmRejectionReason.REMOTE_DELETE_PROOF_REQUIRED
            )
        admission.armRemoteVerified(request, snapshot)
    }

    suspend fun acceptSms(
        admission: PanicAdmissionService,
        envelope: ValidatedSmsEnvelope,
    ): AdmissionResult = mutex.withLock {
        val snapshot = notConfiguredSnapshotOrNull() ?: AdmissionAuthoritySnapshot.unknown()
        admission.acceptSmsVerified(envelope, snapshot)
    }

    suspend fun acceptLocal(
        admission: PanicAdmissionService,
    ): AdmissionResult = mutex.withLock {
        val snapshot = notConfiguredSnapshotOrNull() ?: AdmissionAuthoritySnapshot.unknown()
        admission.acceptLocalVerified(snapshot)
    }

    private fun notConfiguredSnapshotOrNull(): AdmissionAuthoritySnapshot? {
        val ready = (resolveLocked() as? ActiveVaultAuthorityResolution.Ready)?.record
            ?: return null
        return if (
            ready.mode == RemoteDeleteConfiguration.NOT_CONFIGURED &&
            ready.intent == null &&
            ready.status == ActiveVaultAuthorityStatus.READY
        ) {
            AdmissionAuthoritySnapshot.notConfigured()
        } else {
            null
        }
    }

    private fun resolveLocked(): ActiveVaultAuthorityResolution =
        ActiveVaultAuthorityResolver.resolve(
            authorityStore.read(),
            registryStore.read(),
        )

    private suspend fun panicIdleAndUnarmed(): Boolean =
        (panicStore.read() as? PanicStoreReadResult.Ready)?.state?.let {
            it.phase == PanicPhase.IDLE && it.arm == null
        } == true

    private fun committedJournalFor(
        identity: VaultProvisioningJournal,
    ): VaultProvisioningJournal? {
        val read = AtomicVaultProvisioningJournalStore.forVault(context, identity).read()
        val record = (read as? VaultJournalRead.Ready)?.record ?: return null
        return record.takeIf {
            it.vaultIdHex == identity.vaultIdHex &&
                it.generationHex == identity.generationHex &&
                it.epoch == identity.epoch &&
                it.phase == VaultProvisioningPolicy.JournalPhase.COMMITTED
        }
    }

    private fun committedIdentityIsCoherent(identity: VaultProvisioningJournal): Boolean {
        if (identity.phase != VaultProvisioningPolicy.JournalPhase.COMMITTED) return false
        if (
            AtomicVaultProvisioningJournalStore.forVault(context, identity).read() !=
            VaultJournalRead.Ready(identity)
        ) return false
        val registry = (registryStore.read() as? VaultSecurityRegistryRead.Ready)?.record
            ?: return false
        if (
            registry.vaultIdHex != identity.vaultIdHex ||
            registry.generationHex != identity.generationHex ||
            registry.aliases.isEmpty() ||
            registry.aliases.any { !key.aliasExists(it) }
        ) return false
        return rotationIsStable(identity)
    }

    private fun registryMatches(identity: VaultProvisioningJournal): Boolean {
        val registry = (registryStore.read() as? VaultSecurityRegistryRead.Ready)?.record
            ?: return false
        return registry.vaultIdHex == identity.vaultIdHex &&
            registry.generationHex == identity.generationHex &&
            registry.aliases.isNotEmpty() &&
            registry.aliases.all(key::aliasExists)
    }

    private fun rotationIsStable(identity: VaultProvisioningJournal): Boolean {
        val name = identity.vaultIdHex + "." + identity.generationHex + ".bin"
        val file = VerifiedEpochEnvelopeFile(
            File(context.noBackupFilesDir, "security/rotation/" + name),
            TinkVaultSession.MAX_KEYSET_BYTES + 2048,
        )
        val record = file.read()?.let(VaultKekRotationCodec::decode) ?: return true
        if (
            record.vault != identity.vaultIdHex ||
            record.generation != identity.generationHex ||
            record.epoch != identity.epoch ||
            record.phase != VaultKekRotationRecord.Phase.COMMITTED
        ) return false
        val registry = (registryStore.read() as? VaultSecurityRegistryRead.Ready)?.record
            ?: return false
        return registry.vaultIdHex == record.vault &&
            registry.generationHex == record.generation &&
            registry.revision == record.registryRevision + 1 &&
            registry.aliases == listOf(record.newAlias) &&
            !key.aliasExists(record.oldAlias) &&
            key.aliasExists(record.newAlias)
    }

    private suspend fun restoreReadyIfUnchanged(
        blocked: ActiveVaultAuthorityRecord,
        identity: VaultProvisioningJournal,
    ) {
        mutex.withLock {
            if (!panicIdleAndUnarmed()) return@withLock
            if (!rotationIsStable(identity) || !registryMatches(identity)) return@withLock
            if (authorityStore.read() != ActiveVaultAuthorityRead.Ready(blocked)) return@withLock
            authorityStore.replaceExpected(
                blocked,
                blocked.copy(
                    authorityRevision = blocked.authorityRevision + 1,
                    status = ActiveVaultAuthorityStatus.READY,
                ),
            )
        }
    }
}
