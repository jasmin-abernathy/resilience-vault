package org.lepotager.resiliencevault.crypto

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lepotager.resiliencevault.panic.RemoteDeleteConfiguration
import org.lepotager.resiliencevault.panic.RemoteDeleteIntent

class ActiveVaultAuthorityTest {
    private val vault = "1".repeat(64)
    private val generation = "2".repeat(64)

    @Test
    fun codec_round_trip_unknown_and_not_configured() {
        for (record in listOf(
            ActiveVaultAuthorityRecord.adoptedUnknown(vault, generation),
            ActiveVaultAuthorityRecord.freshNotConfigured(vault, generation),
        )) {
            assertEquals(record, ActiveVaultAuthorityCodec.decode(
                ActiveVaultAuthorityCodec.encode(record)
            ))
        }
    }

    @Test
    fun configured_requires_intent_bound_to_same_vault_generation() {
        val intent = RemoteDeleteIntent(
            1,
            "3".repeat(32),
            "tenant-1",
            vault,
            generation,
            "primary",
            "4".repeat(64),
        )
        val record = ActiveVaultAuthorityRecord(
            vault,
            generation,
            1,
            RemoteDeleteConfiguration.CONFIGURED,
            intent,
            ActiveVaultAuthorityOrigin.FRESH_PROVISIONED,
            ActiveVaultAuthorityStatus.READY,
        )
        assertEquals(record, ActiveVaultAuthorityCodec.decode(
            ActiveVaultAuthorityCodec.encode(record)
        ))

        var failed = false
        try {
            record.copy(vaultGenerationHex = "5".repeat(64))
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun corruption_and_unknown_version_fail() {
        val bytes = ActiveVaultAuthorityCodec.encode(
            ActiveVaultAuthorityRecord.adoptedUnknown(vault, generation)
        )
        val corrupt = bytes.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        var corruptFailed = false
        try {
            ActiveVaultAuthorityCodec.decode(corrupt)
        } catch (_: Exception) {
            corruptFailed = true
        }
        assertTrue(corruptFailed)

        val unknown = bytes.copyOf().also {
            it[7] = 2
            val payload = it.copyOfRange(0, it.size - 32)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            digest.copyInto(it, it.size - 32)
        }
        var versionFailed = false
        try {
            ActiveVaultAuthorityCodec.decode(unknown)
        } catch (_: Exception) {
            versionFailed = true
        }
        assertTrue(versionFailed)
    }

    @Test
    fun store_missing_is_not_not_configured_and_failed_write_latches_closed() {
        val file = FakeFile()
        val store = VerifiedActiveVaultAuthorityStore(file)
        assertEquals(ActiveVaultAuthorityRead.Missing, store.read())

        file.failWrite = true
        var failed = false
        try {
            store.createFresh(
                ActiveVaultAuthorityRecord.freshNotConfigured(vault, generation)
            )
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(ActiveVaultAuthorityRead.Unavailable, store.read())
    }

    @Test
    fun resolver_blocks_registry_mismatch_without_mutating_authority() {
        val record = ActiveVaultAuthorityRecord.adoptedUnknown(vault, generation)
        val registry = VaultSecurityRegistryRecord(
            vault,
            "9".repeat(64),
            1,
            listOf("rv.kek.v1." + vault + "." + "9".repeat(64) + ".e1"),
        )

        val resolution = ActiveVaultAuthorityResolver.resolve(
            ActiveVaultAuthorityRead.Ready(record),
            VaultSecurityRegistryRead.Ready(registry),
        )
        assertTrue(resolution is ActiveVaultAuthorityResolution.BlockedTransition)
        assertEquals(record, (resolution as ActiveVaultAuthorityResolution.BlockedTransition).record)
    }

    @Test
    fun store_refuses_configured_without_backend_transaction() {
        val file = FakeFile()
        val store = VerifiedActiveVaultAuthorityStore(file)
        val intent = RemoteDeleteIntent(
            1,
            "3".repeat(32),
            "tenant-1",
            vault,
            generation,
            "primary",
            "4".repeat(64),
        )
        var failed = false
        try {
            store.createFresh(
                ActiveVaultAuthorityRecord(
                    vault,
                    generation,
                    1,
                    RemoteDeleteConfiguration.CONFIGURED,
                    intent,
                    ActiveVaultAuthorityOrigin.FRESH_PROVISIONED,
                    ActiveVaultAuthorityStatus.READY,
                )
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(ActiveVaultAuthorityRead.Missing, store.read())
    }

    private class FakeFile : ActiveVaultAuthorityFile {
        var bytes: ByteArray? = null
        var failWrite = false

        override fun read(): ByteArray =
            bytes?.copyOf() ?: throw FileNotFoundException()

        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("simulated")
            this.bytes = bytes.copyOf()
        }
    }
}
