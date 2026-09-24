package org.lepotager.resiliencevault.crypto

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Keystore-only local epoch wrapping key. This does not serialize key material and is not
 * connected to the vault until provisioning, panic and physical-device tests are complete.
 * Every use of this key requires device authentication; the caller must use an authenticated
 * Cipher operation (BiometricPrompt/CryptoObject) to unwrap E.
 */
class AndroidVaultKek(private val context: Context) {
    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun aliasFor(record: VaultProvisioningJournal): String =
        "rv.kek.v1.${record.vaultIdHex}.${record.generationHex}.e${record.epoch}"

    fun exists(record: VaultProvisioningJournal): Boolean =
        keyStore().containsAlias(aliasFor(record))

    @Synchronized
    fun createExplicitly(
        journal: AtomicVaultProvisioningJournalStore,
        record: VaultProvisioningJournal
    ): SecretKey {
        require(record.phase == VaultProvisioningPolicy.JournalPhase.BEGIN)
        check(journal.read() == VaultJournalRead.Ready(record)) {
            "BEGIN must be committed before generating a KEK"
        }
        val manager = context.getSystemService(KeyguardManager::class.java)
            ?: throw IllegalStateException("Secure lock screen unavailable")
        check(manager.isDeviceSecure) { "A secure device lock is required" }
        val alias = aliasFor(record)
        check(!keyStore().containsAlias(alias)) { "KEK already exists" }

        val spec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    // API 26–29 has no setUserAuthenticationParameters. A per-use key requires
                    // enrolled biometrics there; no unprotected fallback is permitted.
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }.build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec)
        generator.generateKey()
        return lookupExisting(record)
    }

    fun loadExisting(
        journal: AtomicVaultProvisioningJournalStore,
        record: VaultProvisioningJournal
    ): SecretKey {
        check(record.phase != VaultProvisioningPolicy.JournalPhase.BEGIN)
        check(journal.read() == VaultJournalRead.Ready(record)) { "Provisioning journal unavailable" }
        return lookupExisting(record)
    }

    private fun lookupExisting(record: VaultProvisioningJournal): SecretKey =
        (keyStore().getKey(aliasFor(record), null) as? SecretKey)
            ?: throw IllegalStateException("KEK unavailable; do not regenerate")

    /** Panic may call this without unlocking or decrypting the epoch keyset. */
    @Synchronized
    fun deleteExisting(record: VaultProvisioningJournal) {
        val store = keyStore()
        val alias = aliasFor(record)
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        check(!keyStore().containsAlias(alias)) { "KEK deletion unconfirmed" }
    }
}
