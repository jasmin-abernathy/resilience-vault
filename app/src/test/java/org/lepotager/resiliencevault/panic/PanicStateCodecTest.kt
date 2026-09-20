package org.lepotager.resiliencevault.panic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanicStateCodecTest {
    @Test
    fun round_trip_preserves_valid_state() {
        val state = PanicPersistentState(
            phase = PanicPhase.IDLE,
            arm = ArmedRemotePanic(
                generationHex = "a".repeat(64),
                bootId = "42",
                startedElapsedRealtimeMs = 100,
                startedUtcMs = 200,
                durationMs = 3_600_000,
                contacts = listOf(
                    TrustedContactVerifier("+33600000000", "b".repeat(64))
                )
            )
        )

        assertEquals(state, PanicStateCodec.decode(PanicStateCodec.encode(state)))
    }

    @Test
    fun corruption_never_decodes_as_idle() {
        val bytes = PanicStateCodec.encode(PanicPersistentState.initial())
        bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x01).toByte()

        var failed = false
        try {
            PanicStateCodec.decode(bytes)
        } catch (_: PanicStateCorruptionException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun illegal_non_idle_state_is_rejected_before_encoding() {
        PanicStateCodec.encode(
            PanicPersistentState(
                phase = PanicPhase.LOCAL_PENDING,
                panicIdHex = null
            )
        )
    }
}
