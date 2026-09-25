package org.lepotager.resiliencevault.crypto

import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** KEK revision is independent of the E/D epoch: existing objects remain decryptable. */
internal data class VaultKekRotationRecord(
    val vault: String, val generation: String, val epoch: Long,
    val registryRevision: Long, val oldAlias: String, val newAlias: String,
    val phase: Phase, val envelope: List<Byte>,
) {
    enum class Phase { BEGIN, VERIFIED, COMMITTED }
    init {
        canonicalIdBytes(vault); canonicalIdBytes(generation)
        require(epoch > 0 && registryRevision in 2 until Long.MAX_VALUE)
        val prefix = "rv.kek.v1.$vault.$generation.e$epoch"
        require(oldAlias == prefix || oldAlias.matches(Regex(Regex.escape(prefix) + "\\.r[1-9][0-9]*")))
        require(newAlias == "$prefix.r$registryRevision" && oldAlias != newAlias)
        require(envelope.size <= TinkVaultSession.MAX_KEYSET_BYTES + 120)
        require((phase == Phase.BEGIN) == envelope.isEmpty())
    }
}

internal interface VaultKekRotationEffects {
    fun state(): VaultKekRotationRecord?
    fun writeState(expected: VaultKekRotationRecord?, next: VaultKekRotationRecord)
    fun registry(): VaultSecurityRegistryRecord
    fun replaceRegistry(expected: VaultSecurityRegistryRecord, next: VaultSecurityRegistryRecord)
    fun exists(alias: String): Boolean
    fun createKey(record: VaultKekRotationRecord)
    fun localKek(alias: String): LocalKekEnvelope
    fun deleteKey(alias: String)
}

/** Run exclusively under the installation lifecycle lease. Incomplete rotation blocks opening.
 * No implicit resume, regeneration or old-envelope fallback after any interrupted write.
 */
internal class VaultKekRotation(private val effects: VaultKekRotationEffects) {
    suspend fun rotate(session: TinkVaultSession) {
        session.checkLive()
        val previous = effects.state()
        val registry = effects.registry()
        check(registry.vaultIdHex == session.vaultId && registry.generationHex == session.generation)
        check(registry.aliases.size == 1 && registry.revision < Long.MAX_VALUE - 1)
        if (previous != null) validateCommitted(previous, registry, session.epoch)
        else check(registry.revision == 1L && registry.aliases.single() ==
            "rv.kek.v1.${session.vaultId}.${session.generation}.e${session.epoch}")
        val oldAlias = registry.aliases.single()
        check(effects.exists(oldAlias))
        val revision = registry.revision + 1
        val next = VaultKekRotationRecord(session.vaultId, session.generation, session.epoch,
            revision, oldAlias,
            "rv.kek.v1.${session.vaultId}.${session.generation}.e${session.epoch}.r$revision",
            VaultKekRotationRecord.Phase.BEGIN, emptyList())
        check(!effects.exists(next.newAlias))
        active()
        effects.writeState(previous, next) // intent durable before alias creation
        check(effects.state() == next)
        val both = registry.copy(revision = revision, aliases = listOf(oldAlias, next.newAlias).sorted())
        active()
        effects.replaceRegistry(registry, both)
        check(effects.registry() == both)
        active()
        effects.createKey(next)
        check(effects.exists(next.newAlias))
        val envelope = session.wrapLocal(boundEnvelope(next.newAlias))
        val verified = next.copy(phase = VaultKekRotationRecord.Phase.VERIFIED, envelope = envelope.toList())
        active()
        effects.writeState(next, verified)
        check(effects.state() == verified)
        // Verify the persisted envelope actually contains the SAME E, not merely a valid keyset.
        val binding = session.context(VaultBinding.Purpose.MANIFEST, "ff".repeat(32), revision)
        val proof = byteArrayOf(0x52, 0x56, 0x52)
        val sealed = session.sealManifest(binding, proof)
        openEnvelope(verified).use { check(it.openManifest(binding, sealed).contentEquals(proof)) }
        session.checkLive()
        check(effects.registry() == both && effects.state() == verified)
        active()
        effects.deleteKey(oldAlias) // only after durable readback + authenticated SAME-E proof
        check(!effects.exists(oldAlias) && effects.exists(next.newAlias))
        val onlyNew = both.copy(revision = revision + 1, aliases = listOf(next.newAlias))
        active()
        effects.replaceRegistry(both, onlyNew)
        check(effects.registry() == onlyNew)
        val committed = verified.copy(phase = VaultKekRotationRecord.Phase.COMMITTED)
        active()
        effects.writeState(verified, committed)
        check(effects.state() == committed)
    }

    suspend fun openExisting(vault: String, generation: String, epoch: Long): TinkVaultSession {
        val record = checkNotNull(effects.state())
        check(record.vault == vault && record.generation == generation)
        validateCommitted(record, effects.registry(), epoch)
        check(!effects.exists(record.oldAlias) && effects.exists(record.newAlias))
        return openEnvelope(record)
    }

    private fun validateCommitted(record: VaultKekRotationRecord, registry: VaultSecurityRegistryRecord, epoch: Long) {
        check(record.phase == VaultKekRotationRecord.Phase.COMMITTED && record.epoch == epoch)
        check(registry.vaultIdHex == record.vault && registry.generationHex == record.generation &&
            registry.revision == record.registryRevision + 1 && registry.aliases == listOf(record.newAlias))
    }
    private suspend fun openEnvelope(record: VaultKekRotationRecord) = TinkVaultSession.openLocal(
        record.vault, record.generation, record.epoch, record.envelope.toByteArray(), boundEnvelope(record.newAlias))

    private fun boundEnvelope(alias: String): LocalKekEnvelope {
        val delegate = effects.localKek(alias)
        val suffix = alias.toByteArray(Charsets.US_ASCII)
        return object : LocalKekEnvelope {
            override suspend fun encryptKeyset(handle: KeysetHandle, aad: ByteArray): ByteArray =
                delegate.encryptKeyset(handle, aad + suffix)

            override suspend fun decryptKeyset(envelope: ByteArray, aad: ByteArray): KeysetHandle =
                delegate.decryptKeyset(envelope, aad + suffix)
        }
    }

    private suspend fun active() {
        currentCoroutineContext().ensureActive()
    }
}

/** Strict non-secret state codec. Digest detects corruption, not malicious disk rollback. */
internal object VaultKekRotationCodec {
    fun encode(record: VaultKekRotationRecord): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out ->
            out.writeInt(0x52565231)
            out.writeUTF(record.vault); out.writeUTF(record.generation); out.writeLong(record.epoch)
            out.writeLong(record.registryRevision); out.writeUTF(record.oldAlias); out.writeUTF(record.newAlias)
            out.writeByte(record.phase.ordinal); out.writeInt(record.envelope.size)
            out.write(record.envelope.toByteArray())
        }
        val payload = raw.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
    fun decode(bytes: ByteArray): VaultKekRotationRecord {
        require(bytes.size in 32..(TinkVaultSession.MAX_KEYSET_BYTES + 2048))
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        require(MessageDigest.isEqual(bytes.copyOfRange(bytes.size - 32, bytes.size),
            MessageDigest.getInstance("SHA-256").digest(payload)))
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == 0x52565231)
            val vault = input.readUTF(); val generation = input.readUTF(); val epoch = input.readLong()
            val revision = input.readLong(); val old = input.readUTF(); val new = input.readUTF()
            val phase = VaultKekRotationRecord.Phase.entries.getOrNull(input.readUnsignedByte())
                ?: error("Unknown rotation phase")
            val size = input.readInt()
            require(size in 0..(TinkVaultSession.MAX_KEYSET_BYTES + 120))
            val envelope = ByteArray(size).also(input::readFully)
            require(input.available() == 0)
            VaultKekRotationRecord(vault, generation, epoch, revision, old, new, phase, envelope.toList())
        }
    }
}
