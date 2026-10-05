package org.lepotager.resiliencevault.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.lepotager.resiliencevault.crypto.VerifiedEpochEnvelopeFile
import org.lepotager.resiliencevault.panic.RemoteDeleteIntent

/**
 * Local DELETE-only authority. No product flow provisions it yet: cloud provisioning is absent.
 *
 * The KEK deliberately has no user-auth requirement so a post-panic retry can survive lock/reboot.
 * It must never be used for E, READ credentials or any data decryption authority.
 */
internal class AndroidDeleteOnlyCapsuleOwner(context: Context) : DeleteOnlyCapsuleReader {
    private val app = context.applicationContext
    private val provisioner = DeleteOnlyCapsuleProvisioner(
        AndroidDeleteOnlyKekPort(),
        AndroidDeleteOnlyCapsuleFilePort(app),
    )

    fun provision(
        identity: DeleteOnlyCapsuleIdentity,
        credential: DeleteOnlyCredential,
    ): RemoteDeleteIntent =
        provisioner.provision(identity, credential)

    override fun open(intent: RemoteDeleteIntent): DeleteOnlyCredential =
        provisioner.open(intent)
}

private class AndroidDeleteOnlyKekPort : DeleteOnlyCapsuleKeyPort {
    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun alias(identity: DeleteOnlyCapsuleIdentity): String =
        CredentialNamespaces.DELETE_ONLY_ALIAS_PREFIX +
            identity.vaultIdHex + "." + identity.vaultGenerationHex

    override fun exists(identity: DeleteOnlyCapsuleIdentity): Boolean =
        keyStore().containsAlias(alias(identity))

    @Synchronized
    override fun create(identity: DeleteOnlyCapsuleIdentity): SecretKey {
        identity.validate()
        val alias = alias(identity)
        check(!keyStore().containsAlias(alias)) { "Delete-only KEK already exists" }

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(spec)
        generator.generateKey()
        return load(identity)
    }

    override fun load(identity: DeleteOnlyCapsuleIdentity): SecretKey {
        identity.validate()
        val key = keyStore().getKey(alias(identity), null) as? SecretKey
            ?: throw IllegalStateException("Delete-only KEK unavailable; do not regenerate")
        check(key.encoded == null) { "Exportable delete-only KEK refused" }
        return key
    }
}

private class AndroidDeleteOnlyCapsuleFilePort(
    private val context: Context,
) : DeleteOnlyCapsuleFilePort {
    override fun read(capsuleIdHex: String): ByteArray? =
        verifiedFile(capsuleIdHex).read()

    override fun writeNew(capsuleIdHex: String, bytes: ByteArray) {
        check(read(capsuleIdHex) == null) { "Delete-only capsule already exists" }
        verifiedFile(capsuleIdHex).write(bytes)
        check(read(capsuleIdHex)?.contentEquals(bytes) == true) {
            "Delete-only capsule write unconfirmed"
        }
    }

    private fun verifiedFile(capsuleIdHex: String): VerifiedEpochEnvelopeFile {
        require(
            (capsuleIdHex.length == 32 || capsuleIdHex.length == 64) &&
                capsuleIdHex.all { it in '0'..'9' || it in 'a'..'f' }
        ) { "Invalid delete-only capsule id" }

        val noBackup = context.noBackupFilesDir
        val credentials = File(noBackup, "credentials")
        val root = CredentialNamespaces.deleteOnlyDirectory(context)
        val candidate = File(root, capsuleIdHex + ".bin")

        for (component in listOf(credentials, root, candidate)) {
            if (component.exists()) {
                check(!Files.isSymbolicLink(component.toPath())) {
                    "Delete-only capsule path must not contain symlinks"
                }
            }
        }

        val trustedRoot = noBackup.canonicalFile.toPath()
        val candidateCanonical = candidate.canonicalFile.toPath()
        check(candidateCanonical.startsWith(trustedRoot)) {
            "Delete-only capsule escaped noBackupFilesDir"
        }
        check(candidate.name == capsuleIdHex + ".bin")
        return VerifiedEpochEnvelopeFile(
            candidate,
            DeleteOnlyCapsuleCodec.MAX_CONTAINER_BYTES,
        )
    }
}
