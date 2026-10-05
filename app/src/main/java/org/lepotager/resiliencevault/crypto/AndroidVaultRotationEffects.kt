package org.lepotager.resiliencevault.crypto

import android.content.Context
import java.io.File

internal class AndroidVaultRotationEffects(
    context: Context,
    private val identity: VaultProvisioningJournal,
    private val prompt: AuthenticatedCipherPrompt,
) : VaultKekRotationEffects {
    private val key = AndroidVaultKek(context)
    private val registryStore = AtomicVaultSecurityRegistryStore.create(context)
    private val file = VerifiedEpochEnvelopeFile(File(context.noBackupFilesDir,
        "security/rotation/${identity.vaultIdHex}.${identity.generationHex}.bin"),
        TinkVaultSession.MAX_KEYSET_BYTES + 2048)

    override fun state(): VaultKekRotationRecord? = file.read()?.let {
        VaultKekRotationCodec.decode(it).also { record ->
            check(record.vault == identity.vaultIdHex && record.generation == identity.generationHex &&
                record.epoch == identity.epoch)
        }
    }
    override fun writeState(expected: VaultKekRotationRecord?, next: VaultKekRotationRecord) {
        check(state() == expected)
        file.write(VaultKekRotationCodec.encode(next))
        check(state() == next)
    }
    override fun registry(): VaultSecurityRegistryRecord =
        (registryStore.read() as? VaultSecurityRegistryRead.Ready)?.record ?: error("Registry unavailable")
    override fun replaceRegistry(expected: VaultSecurityRegistryRecord, next: VaultSecurityRegistryRecord) =
        registryStore.replaceExpected(expected, next)
    override fun exists(alias: String) = key.aliasExists(alias)
    override fun createKey(record: VaultKekRotationRecord) = key.createForRotation(record, this)
    override fun localKek(alias: String): LocalKekEnvelope =
        AuthenticatedLocalKekEnvelope({ key.lookupAlias(alias) }, prompt)
    override fun deleteKey(alias: String) = key.deleteAlias(alias)
}
