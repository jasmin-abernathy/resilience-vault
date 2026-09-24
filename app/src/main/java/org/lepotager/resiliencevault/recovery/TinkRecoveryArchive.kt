package org.lepotager.resiliencevault.recovery

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.lepotager.resiliencevault.crypto.TinkVaultSession
import org.lepotager.resiliencevault.crypto.VaultBinding
import org.lepotager.resiliencevault.crypto.aead
import org.lepotager.resiliencevault.crypto.canonicalIdBytes
import org.lepotager.resiliencevault.crypto.sha256Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Versioned portable archive. Only ciphertexts remain after verification; no disk plaintext. */
internal object TinkRecoveryArchive {
    const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    private const val MAX_OBJECTS = 128
    private const val KIT_MAGIC = 0x52564b31 // RVK1
    private const val ARCHIVE_MAGIC = 0x52564131 // RVA1
    private const val MANIFEST_MAGIC = 0x52564d32 // RVM2: strict binary, no JSON fallback

    internal data class ObjectRecord(val binding: VaultBinding, val plaintextBytes: Int, val ciphertext: ByteArray) {
        override fun toString() = "ObjectRecord[redacted]"
    }

    internal class Export(val binding: RecoveryArtifactBinding, val kit: ByteArray, val archive: ByteArray) {
        override fun toString() = "RecoveryExport[secret kit redacted]"
    }

    /** Caller supplies immutable objects from one expected epoch; all are authenticated before export. */
    fun prepare(session: TinkVaultSession, manifestId: String, revision: Long, objects: List<ObjectRecord>): Export {
        require(objects.size in 1..MAX_OBJECTS)
        require(objects.map { it.binding.objectIdHex }.distinct().size == objects.size)
        require(objects.sumOf { it.ciphertext.size.toLong() } <= MAX_ARCHIVE_BYTES - 2 * 1024 * 1024)
        val manifestContext = session.context(VaultBinding.Purpose.MANIFEST, manifestId, revision)
        val manifestBytes = encode { out ->
            out.writeInt(MANIFEST_MAGIC); out.writeInt(objects.size)
            objects.forEach { record ->
                val clear = session.decryptObject(record.binding, record.ciphertext)
                try { require(clear.size == record.plaintextBytes) } finally { clear.fill(0) }
                out.write(canonicalIdBytes(record.binding.objectIdHex))
                out.writeLong(record.binding.revision)
                out.writeInt(record.plaintextBytes)
                out.writeInt(record.ciphertext.size)
                out.write(canonicalIdBytes(record.ciphertext.sha256Hex()))
            }
        }
        val manifest = try { session.sealManifest(manifestContext, manifestBytes) } finally { manifestBytes.fill(0) }
        val binding = RecoveryArtifactBinding(session.vaultId, session.generation, manifest.sha256Hex())
        val identity = encode { out ->
            out.write(canonicalIdBytes(binding.vaultIdHex)); out.write(canonicalIdBytes(binding.generationHex))
            out.write(canonicalIdBytes(binding.headHex)); out.writeLong(session.epoch)
            out.write(canonicalIdBytes(manifestId)); out.writeLong(revision)
        }
        val recovery = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        val serializedR = TinkVaultSession.externalKeyBytes(recovery)
        val kit = try {
            val prefix = encode { it.writeInt(KIT_MAGIC); it.write(identity); it.field(serializedR) }
            prefix + encode { it.field(recovery.aead().encrypt(byteArrayOf(), prefix)) }
        } finally { serializedR.fill(0) }
        val wrappedE = session.wrapRecovery(recovery, binding.headHex)
        val archive = encode { out ->
            out.writeInt(ARCHIVE_MAGIC); out.write(identity); out.field(wrappedE); out.field(manifest)
            out.writeInt(objects.size); objects.forEach { out.field(it.ciphertext) }
        }
        require(archive.size <= MAX_ARCHIVE_BYTES)
        session.checkLive()
        return Export(binding, kit, archive)
    }

    /** Expected head is an independent anchor supplied by the owner, not read from this archive. */
    fun verify(kitSource: () -> InputStream, archiveSource: () -> InputStream,
               expected: RecoveryArtifactBinding, scope: RecoveryArchiveDeleteScope): VerifiedBundle {
        val kitBytes = kitSource().use { readBounded(it, 128 * 1024) }
        try {
            val archive = archiveSource().use { readBounded(it, MAX_ARCHIVE_BYTES) }
            val kitInput = DataInputStream(ByteArrayInputStream(kitBytes))
            val kitIdentity: Identity
            val recovery: KeysetHandle
            kitInput.use { input ->
                require(input.readInt() == KIT_MAGIC)
                kitIdentity = input.identity()
                require(kitIdentity.binding == expected)
                val rawR = input.field(TinkVaultSession.MAX_KEYSET_BYTES)
                recovery = try { TinkVaultSession.readExternalKey(rawR) } finally { rawR.fill(0) }
                val prefixLength = kitBytes.size - input.available()
                val tag = input.field(1024)
                require(input.read() == -1)
                require(recovery.aead().decrypt(tag, kitBytes.copyOfRange(0, prefixLength)).isEmpty())
            }
            var session: TinkVaultSession? = null
            try {
                val records = DataInputStream(ByteArrayInputStream(archive)).use { input ->
                    require(input.readInt() == ARCHIVE_MAGIC)
                    val identity = input.identity()
                    require(identity == kitIdentity)
                    val envelope = input.field(TinkVaultSession.MAX_KEYSET_BYTES + 152)
                    session = TinkVaultSession.openRecovery(expected.vaultIdHex, expected.generationHex,
                        identity.epoch, expected.headHex, envelope, recovery)
                    val activeSession = checkNotNull(session)
                    val manifest = input.field(TinkVaultSession.MAX_MANIFEST_BYTES + 256)
                    require(manifest.sha256Hex() == expected.headHex)
                    val plainManifest = activeSession.openManifest(identity.manifestContext(), manifest)
                    val specs = try { decodeManifest(plainManifest, identity) } finally { plainManifest.fill(0) }
                    require(input.readInt() == specs.size)
                    val result = specs.map { spec ->
                        val blob = input.field(TinkVaultSession.MAX_CONTAINER_BYTES)
                        require(blob.size == spec.ciphertextBytes && blob.sha256Hex() == spec.digest)
                        val clear = activeSession.decryptObject(spec.binding, blob)
                        try { require(clear.size == spec.plaintextBytes) } finally { clear.fill(0) }
                        ObjectRecord(spec.binding, spec.plaintextBytes, blob)
                    }
                    require(input.read() == -1) { "Trailing archive bytes" }
                    result
                }
                return VerifiedBundle(checkNotNull(session), records,
                    VerifiedExternalRecoveryKit(expected, kitBytes.size.toLong(), kitBytes.sha256Hex()),
                    VerifiedCompleteRecoveryArchive(expected, records.size.toLong(), archive.size.toLong(), archive.sha256Hex(), scope))
            } catch (error: Exception) { session?.close(); throw error }
        } finally { kitBytes.fill(0) }
    }

    internal class VerifiedBundle internal constructor(
        private val session: TinkVaultSession,
        private val records: List<ObjectRecord>,
        val kitEvidence: VerifiedExternalRecoveryKit,
        val archiveEvidence: VerifiedCompleteRecoveryArchive,
    ) : AutoCloseable {
        fun trial(): VerifiedTrialRestore {
            records.forEach {
                val clear = session.decryptObject(it.binding, it.ciphertext)
                try { check(clear.size == it.plaintextBytes) } finally { clear.fill(0) }
            }
            return VerifiedTrialRestore(kitEvidence.binding, records.size.toLong())
        }

        /** No source E/KEK is installed on B: all objects are re-encrypted under fresh E and D. */
        fun recoverIntoFreshSession(target: TinkVaultSession): List<ObjectRecord> {
            require(target.vaultId != session.vaultId && target.generation != session.generation)
            require(target.epoch == 1L)
            return records.map { old ->
                val clear = session.decryptObject(old.binding, old.ciphertext)
                try {
                    val newId = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    val binding = target.context(VaultBinding.Purpose.OBJECT_DATA, newId, 1)
                    ObjectRecord(binding, clear.size, target.encryptObject(binding, clear))
                } finally { clear.fill(0) }
            }.also { session.checkLive(); target.checkLive() }
        }
        override fun close() = session.close()
        override fun toString() = "VerifiedRecoveryBundle[redacted]"
    }

    /** Active-head evidence comes from an authenticated manifest and an independent expected digest. */
    fun verifyActiveHead(session: TinkVaultSession, context: VaultBinding, source: () -> InputStream,
                         expectedHead: String): VerifiedActiveVaultHead {
        val bytes = source().use { readBounded(it, TinkVaultSession.MAX_MANIFEST_BYTES + 256) }
        require(bytes.sha256Hex() == expectedHead)
        val plain = session.openManifest(context, bytes)
        try { decodeManifest(plain, Identity(RecoveryArtifactBinding(session.vaultId, session.generation, expectedHead),
            session.epoch, context.objectIdHex, context.revision)) } finally { plain.fill(0) }
        return VerifiedActiveVaultHead(RecoveryArtifactBinding(session.vaultId, session.generation, expectedHead))
    }

    private data class Identity(val binding: RecoveryArtifactBinding, val epoch: Long, val manifestId: String, val revision: Long) {
        init { require(epoch > 0 && revision > 0 && manifestId != TinkVaultSession.ZERO_ID) }
        fun manifestContext() = VaultBinding(VaultBinding.Purpose.MANIFEST, binding.vaultIdHex,
            binding.generationHex, manifestId, epoch, revision)
    }
    private data class Spec(val binding: VaultBinding, val plaintextBytes: Int, val ciphertextBytes: Int, val digest: String)
    private fun decodeManifest(bytes: ByteArray, identity: Identity): List<Spec> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MANIFEST_MAGIC)
            val count = input.readInt(); require(count in 1..MAX_OBJECTS)
            val specs = List(count) {
                val id = input.id(); val revision = input.readLong()
                val plain = input.readInt(); val cipher = input.readInt(); val hash = input.id()
                require(id != TinkVaultSession.ZERO_ID && revision > 0)
                require(plain in 0..TinkVaultSession.MAX_OBJECT_BYTES && cipher in 1..TinkVaultSession.MAX_CONTAINER_BYTES)
                Spec(VaultBinding(VaultBinding.Purpose.OBJECT_DATA, identity.binding.vaultIdHex,
                    identity.binding.generationHex, id, identity.epoch, revision), plain, cipher, hash)
            }
            require(specs.map { it.binding.objectIdHex }.distinct().size == count)
            require(input.read() == -1)
            specs
        }
    private fun DataInputStream.identity(): Identity = Identity(RecoveryArtifactBinding(id(), id(), id()), readLong(), id(), readLong())
    private fun DataInputStream.id(): String = ByteArray(32).also(::readFully).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun DataOutputStream.field(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
    private fun DataInputStream.field(max: Int): ByteArray {
        val size = readInt(); require(size in 1..max)
        return ByteArray(size).also(::readFully)
    }
    private fun encode(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().let { bytes ->
        DataOutputStream(bytes).use(block); bytes.toByteArray()
    }
    private fun readBounded(input: InputStream, max: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(read > 0 && bytes.size().toLong() + read <= max)
            bytes.write(buffer, 0, read)
        }
        return bytes.toByteArray()
    }
}
