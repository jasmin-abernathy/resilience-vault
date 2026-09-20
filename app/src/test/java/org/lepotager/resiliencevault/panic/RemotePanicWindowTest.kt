package org.lepotager.resiliencevault.panic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePanicWindowTest {
    private val arm = ArmedRemotePanic(
        generationHex = "a".repeat(64),
        bootId = "boot-1",
        startedElapsedRealtimeMs = 10_000L,
        startedUtcMs = 1_000_000L,
        durationMs = 3_600_000L,
        contacts = listOf(
            TrustedContactVerifier("+33600000000", "b".repeat(64))
        )
    )

    @Test
    fun valid_before_boundary_and_invalid_at_boundary() {
        assertTrue(
            RemotePanicWindow.isValid(
                arm,
                PanicClockSnapshot("boot-1", 10_000L + 3_600_000L - 1, 1_000_000L + 3_600_000L - 1)
            )
        )
        assertFalse(
            RemotePanicWindow.isValid(
                arm,
                PanicClockSnapshot("boot-1", 10_000L + 3_600_000L, 1_000_000L + 3_600_000L)
            )
        )
    }

    @Test
    fun reboot_and_clock_drift_fail_closed() {
        assertFalse(
            RemotePanicWindow.isValid(
                arm,
                PanicClockSnapshot("boot-2", 10_010L, 1_000_010L)
            )
        )
        assertFalse(
            RemotePanicWindow.isValid(
                arm,
                PanicClockSnapshot("boot-1", 10_010L, 1_003_000L)
            )
        )
    }
}
