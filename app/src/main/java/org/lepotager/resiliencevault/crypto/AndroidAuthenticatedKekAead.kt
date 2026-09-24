package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Real per-use CryptoObject authorization must complete for this exact Cipher or throw.
 * No callback is wired into UI yet. This adapter never weakens the Keystore key policy.
 */
internal fun interface PerUseCipherAuthorization { fun authorize(cipher: Cipher) }

internal class AndroidAuthenticatedKekAead(
    private val loadExisting: () -> SecretKey,
    private val authorization: PerUseCipherAuthorization,
) : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        require(plaintext.size <= TinkVaultSession.MAX_KEYSET_BYTES)
        val key = loadExisting()
        check(key.encoded == null) { "Local KEK must be non-exportable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        authorization.authorize(cipher)
        val encrypted = cipher.doFinal(plaintext)
        val iv = cipher.iv
        if (iv.size != 12) throw GeneralSecurityException("Unexpected Keystore IV")
        return byteArrayOf(1) + iv + encrypted
    }
    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        require(ciphertext.size in 29..(TinkVaultSession.MAX_KEYSET_BYTES + 29))
        require(ciphertext[0] == 1.toByte()) { "Unknown KEK envelope version" }
        val key = loadExisting()
        check(key.encoded == null) { "Local KEK must be non-exportable" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
        cipher.updateAAD(associatedData)
        authorization.authorize(cipher)
        return cipher.doFinal(ciphertext, 13, ciphertext.size - 13)
    }
    override fun toString() = "AndroidAuthenticatedKekAead[redacted]"
}
