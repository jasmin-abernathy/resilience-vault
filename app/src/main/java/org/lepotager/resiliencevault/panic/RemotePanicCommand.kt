package org.lepotager.resiliencevault.panic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

object RemotePanicCommand {
    private val e164 = Regex("^\\+[1-9][0-9]{6,14}$")
    private val lowerHex256 = Regex("^[0-9a-f]{64}$")

    data class Parsed(
        val generationHex: String,
        val secretHex: String
    ) {
        override fun toString(): String = "Parsed([redacted])"
    }

    fun isCanonicalE164(value: String): Boolean = e164.matches(value)

    fun isLowerHex256(value: String?): Boolean =
        value != null && lowerHex256.matches(value)

    fun build(generationHex: String, secretHex: String): String {
        require(isLowerHex256(generationHex))
        require(isLowerHex256(secretHex))
        return "RV1 $generationHex $secretHex"
    }

    fun parseExact(body: String): Parsed? {
        if (body.length != 133) return null
        if (!body.all { it.code in 0..127 }) return null
        val parts = body.split(' ')
        if (parts.size != 3 || parts[0] != "RV1") return null
        if (!isLowerHex256(parts[1]) || !isLowerHex256(parts[2])) return null
        return Parsed(parts[1], parts[2])
    }

    fun verifierHex(
        generationHex: String,
        e164: String,
        secretHex: String
    ): String {
        require(isLowerHex256(generationHex))
        require(isCanonicalE164(e164))
        require(isLowerHex256(secretHex))
        val material = "RV1\u0000$generationHex\u0000$e164\u0000$secretHex"
            .toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.getInstance("SHA-256").digest(material).toHex()
    }

    fun verifierMatches(
        expectedVerifierHex: String,
        generationHex: String,
        e164: String,
        secretHex: String
    ): Boolean {
        if (!isLowerHex256(expectedVerifierHex)) return false
        val actual = verifierHex(generationHex, e164, secretHex)
        return MessageDigest.isEqual(expectedVerifierHex.hexToBytes(), actual.hexToBytes())
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

class RemotePanicTokenGenerator(
    private val secureRandom: SecureRandom = SecureRandom()
) {
    fun newHex256(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
