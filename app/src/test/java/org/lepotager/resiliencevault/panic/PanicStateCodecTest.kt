package org.lepotager.resiliencevault.panic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanicStateCodecTest {
    private val intent = RemoteDeleteIntent(
        capsuleFormatVersion = 1,
        capsuleIdHex = "1".repeat(32),
        tenantId = "tenant-1",
        vaultIdHex = "2".repeat(64),
        vaultGenerationHex = "3".repeat(64),
        serviceId = "primary",
        capsuleSha256Hex = "4".repeat(64),
    )

    @Test
    fun v2_round_trip_preserves_intent_and_checkpoint() {
        val state = PanicPersistentState(
            phase = PanicPhase.POST_PENDING,
            panicIdHex = "a".repeat(64),
            purgeComplete = true,
            remoteDeleteConfiguration = RemoteDeleteConfiguration.CONFIGURED,
            remoteDeleteCheckpoint = RemoteDeleteCheckpoint.TOMBSTONED_PENDING,
            remoteDeleteIntent = intent,
        )

        val encoded = PanicStateCodec.encode(state)
        assertEquals(2, LegacyPanicStateV1Fixture.version(encoded))
        assertEquals(state, PanicStateCodec.decode(encoded))
    }

    @Test
    fun v1_idle_with_and_without_arm_migrates_to_unknown_without_rewriting_semantics() {
        val arm = ArmedRemotePanic(
            generationHex = "a".repeat(64),
            bootId = "boot",
            startedElapsedRealtimeMs = 10,
            startedUtcMs = 20,
            durationMs = 3_600_000,
            contacts = listOf(TrustedContactVerifier("+33600000000", "b".repeat(64))),
        )

        for (candidate in listOf(null, arm)) {
            val decoded = PanicStateCodec.decode(
                LegacyPanicStateV1Fixture.encode("IDLE", arm = candidate)
            )
            assertEquals(PanicPhase.IDLE, decoded.phase)
            assertEquals(candidate, decoded.arm)
            assertEquals(RemoteDeleteConfiguration.UNKNOWN, decoded.remoteDeleteConfiguration)
            assertNull(decoded.remoteDeleteCheckpoint)
            assertNull(decoded.remoteDeleteIntent)
            assertTrue(!decoded.legacyRemoteUnproven)
        }
    }

    @Test
    fun v1_local_and_post_preserve_phase_and_historical_remote_bit_without_inventing_intent() {
        val local = PanicStateCodec.decode(
            LegacyPanicStateV1Fixture.encode(
                phase = "LOCAL_PENDING",
                panicIdHex = "c".repeat(64),
            )
        )
        assertEquals(PanicPhase.LOCAL_PENDING, local.phase)
        assertEquals(RemoteDeleteCheckpoint.LEGACY_UNPROVEN, local.remoteDeleteCheckpoint)
        assertTrue(local.legacyRemoteUnproven)
        assertTrue(!local.legacyRemoteDeleteComplete)
        assertNull(local.remoteDeleteIntent)

        val post = PanicStateCodec.decode(
            LegacyPanicStateV1Fixture.encode(
                phase = "POST_PENDING",
                panicIdHex = "d".repeat(64),
                purgeComplete = true,
                sessionRevocationComplete = true,
                remoteDeleteComplete = true,
            )
        )
        assertEquals(PanicPhase.POST_PENDING, post.phase)
        assertEquals(RemoteDeleteCheckpoint.LEGACY_UNPROVEN, post.remoteDeleteCheckpoint)
        assertTrue(post.legacyRemoteUnproven)
        assertTrue(post.legacyRemoteDeleteComplete)
        assertNull(post.remoteDeleteIntent)
    }

    @Test
    fun v1_complete_becomes_terminal_legacy_not_v2_complete() {
        val decoded = PanicStateCodec.decode(
            LegacyPanicStateV1Fixture.encode(
                phase = "COMPLETE",
                panicIdHex = "e".repeat(64),
                purgeComplete = true,
                sessionRevocationComplete = true,
                remoteDeleteComplete = true,
            )
        )

        assertEquals(PanicPhase.LEGACY_COMPLETE_UNVERIFIED, decoded.phase)
        assertEquals(RemoteDeleteCheckpoint.LEGACY_UNPROVEN, decoded.remoteDeleteCheckpoint)
        assertTrue(decoded.legacyRemoteUnproven)
        assertTrue(decoded.legacyRemoteDeleteComplete)
        assertTrue(!decoded.remoteDeleteAllowsV2Completion())
    }

    @Test
    fun corruption_and_unknown_version_never_decode_as_idle() {
        val bytes = PanicStateCodec.encode(PanicPersistentState.initial())
        bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x01).toByte()
        assertCorrupt(bytes)

        val unknown = PanicStateCodec.encode(PanicPersistentState.initial())
        unknown[4] = 0
        unknown[5] = 0
        unknown[6] = 0
        unknown[7] = 99
        assertCorrupt(unknown)
    }

    @Test(expected = IllegalArgumentException::class)
    fun v2_complete_requires_real_remote_completion_or_proven_not_configured() {
        PanicStateCodec.encode(
            PanicPersistentState(
                phase = PanicPhase.COMPLETE,
                panicIdHex = "f".repeat(64),
                purgeComplete = true,
                sessionRevocationComplete = true,
                remoteDeleteConfiguration = RemoteDeleteConfiguration.UNKNOWN,
                remoteDeleteCheckpoint = RemoteDeleteCheckpoint.LEGACY_UNPROVEN,
                legacyRemoteUnproven = true,
            )
        )
    }

    private fun assertCorrupt(bytes: ByteArray) {
        var failed = false
        try {
            PanicStateCodec.decode(bytes)
        } catch (_: PanicStateCorruptionException) {
            failed = true
        }
        assertTrue(failed)
    }
}
