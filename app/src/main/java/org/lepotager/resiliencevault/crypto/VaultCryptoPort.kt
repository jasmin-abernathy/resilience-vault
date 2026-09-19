package org.lepotager.resiliencevault.crypto

import java.io.InputStream
import java.io.OutputStream

interface VaultCryptoPort {
    suspend fun encrypt(source: InputStream, destination: OutputStream)
    suspend fun decrypt(source: InputStream, destination: OutputStream)
    suspend fun destroyLocalKeyMaterial()
    suspend fun hasUsableKeyMaterial(): Boolean
}

class CryptoNotReadyException :
    IllegalStateException("Production vault cryptography is not enabled yet.")
