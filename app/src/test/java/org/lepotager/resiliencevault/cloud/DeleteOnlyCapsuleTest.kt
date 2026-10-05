package org.lepotager.resiliencevault.cloud

import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteOnlyCapsuleTest {
    private val identity = DeleteOnlyCapsuleIdentity(
        capsuleIdHex = "1".repeat(32),
        tenantId = "tenant-1",
        vaultIdHex = "2".repeat(64),
        vaultGenerationHex = "3".repeat(64),
        serviceId = "primary",
    )
    private val token = ByteArray(DeleteOnlyCredential.TOKEN_BYTES) { (it + 1).toByte() }

    @Test
    fun round_trip_binds_hash_and_complete_identity() {
        val key = softwareKey()
        val credential = DeleteOnlyCredential.fromBytes(token)
        val container = DeleteOnlyCapsuleCodec.seal(identity, credential, key)
        val intent = identity.toIntent(DeleteOnlyCapsuleCodec.sha256Hex(container))

        assertArrayEquals(
            token,
            DeleteOnlyCapsuleCodec.open(intent, container, key).copyToken(),
        )

        for (wrong in listOf(
            intent.copy(tenantId = "tenant-2"),
            intent.copy(vaultIdHex = "4".repeat(64)),
            intent.copy(vaultGenerationHex = "5".repeat(64)),
            intent.copy(serviceId = "secondary"),
            intent.copy(capsuleIdHex = "6".repeat(32)),
        )) {
            assertThrows(Exception::class.java) {
                DeleteOnlyCapsuleCodec.open(wrong, container, key)
            }
        }

        assertThrows(Exception::class.java) {
            DeleteOnlyCapsuleCodec.open(
                intent.copy(capsuleSha256Hex = "f".repeat(64)),
                container,
                key,
            )
        }

        assertThrows(Exception::class.java) {
            DeleteOnlyCapsuleCodec.open(intent, container, softwareKey())
        }
    }

    @Test
    fun corruption_and_unknown_container_version_never_open() {
        val key = softwareKey()
        val credential = DeleteOnlyCredential.fromBytes(token)
        val container = DeleteOnlyCapsuleCodec.seal(identity, credential, key)

        val corrupt = container.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThrows(Exception::class.java) {
            DeleteOnlyCapsuleCodec.open(
                identity.toIntent(DeleteOnlyCapsuleCodec.sha256Hex(corrupt)),
                corrupt,
                key,
            )
        }

        val wrongVersion = container.copyOf().also { it[4] = 2 }
        assertThrows(Exception::class.java) {
            DeleteOnlyCapsuleCodec.open(
                identity.toIntent(DeleteOnlyCapsuleCodec.sha256Hex(wrongVersion)),
                wrongVersion,
                key,
            )
        }
    }

    @Test
    fun provisioner_verifies_readback_and_reopens_exact_token() {
        val keys = FakeKeys()
        val files = FakeFiles()
        val provisioner = DeleteOnlyCapsuleProvisioner(keys, files)
        val credential = DeleteOnlyCredential.fromBytes(token)

        val intent = provisioner.provision(identity, credential)
        assertEquals(identity.capsuleIdHex, intent.capsuleIdHex)
        assertArrayEquals(token, provisioner.open(intent).copyToken())
        assertTrue(keys.exists(identity))
        assertTrue(files.read(identity.capsuleIdHex) != null)
    }

    @Test
    fun failed_write_leaves_orphan_key_and_never_regenerates_it() {
        val keys = FakeKeys()
        val files = FakeFiles().apply { failWrite = true }
        val provisioner = DeleteOnlyCapsuleProvisioner(keys, files)
        val credential = DeleteOnlyCredential.fromBytes(token)

        assertThrows(IOException::class.java) {
            provisioner.provision(identity, credential)
        }
        assertTrue(keys.exists(identity))

        files.failWrite = false
        assertThrows(IllegalStateException::class.java) {
            provisioner.provision(identity, credential)
        }
        assertFalse(files.hasBytes())
    }

    @Test
    fun readback_mismatch_is_never_reported_as_provisioned() {
        val keys = FakeKeys()
        val files = FakeFiles().apply { corruptReadbackAfterWrite = true }
        val provisioner = DeleteOnlyCapsuleProvisioner(keys, files)

        assertThrows(IllegalStateException::class.java) {
            provisioner.provision(
                identity,
                DeleteOnlyCredential.fromBytes(token),
            )
        }
        assertTrue(keys.exists(identity))
    }

    @Test
    fun closing_credential_zeroizes_authority_and_prevents_reuse() {
        val credential = DeleteOnlyCredential.fromBytes(token)
        assertArrayEquals(token, credential.copyToken())
        credential.close()
        credential.close()
        assertThrows(IllegalStateException::class.java) {
            credential.copyToken()
        }
    }

    @Test
    fun credential_string_never_contains_token() {
        val credential = DeleteOnlyCredential.fromBytes(token)
        val text = credential.toString()
        assertEquals("DeleteOnlyCredential([redacted])", text)
        assertFalse(text.contains(token.joinToString()))
    }

    private fun softwareKey(): SecretKey =
        KeyGenerator.getInstance("AES").run {
            init(256)
            generateKey()
        }

    private class FakeKeys : DeleteOnlyCapsuleKeyPort {
        private val keys = mutableMapOf<DeleteOnlyCapsuleIdentity, SecretKey>()

        override fun exists(identity: DeleteOnlyCapsuleIdentity): Boolean =
            identity in keys

        override fun create(identity: DeleteOnlyCapsuleIdentity): SecretKey {
            check(identity !in keys)
            val key = KeyGenerator.getInstance("AES").run {
                init(256)
                generateKey()
            }
            keys[identity] = key
            return key
        }

        override fun load(identity: DeleteOnlyCapsuleIdentity): SecretKey =
            checkNotNull(keys[identity])
    }

    private class FakeFiles : DeleteOnlyCapsuleFilePort {
        private var bytes: ByteArray? = null
        var failWrite = false
        var corruptReadbackAfterWrite = false
        private var wrote = false

        override fun read(capsuleIdHex: String): ByteArray? {
            val current = bytes?.copyOf() ?: return null
            if (wrote && corruptReadbackAfterWrite) {
                current[current.lastIndex] = (current.last().toInt() xor 1).toByte()
            }
            return current
        }

        override fun writeNew(capsuleIdHex: String, bytes: ByteArray) {
            if (failWrite) throw IOException("simulated disk failure")
            check(this.bytes == null)
            this.bytes = bytes.copyOf()
            wrote = true
        }

        fun hasBytes(): Boolean = bytes != null
    }
}
