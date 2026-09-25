package org.lepotager.resiliencevault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import org.lepotager.resiliencevault.panic.PanicInitializationResult
import org.lepotager.resiliencevault.panic.PanicPersistentState
import org.lepotager.resiliencevault.panic.PanicPhase
import org.lepotager.resiliencevault.panic.PanicStoreFailure
import org.lepotager.resiliencevault.panic.PanicStoreReadResult

class FirstInstallSecurityCeremonyTest {
    private class Marker(
        var read: FirstInstallMarkerRead = FirstInstallMarkerRead.Missing,
        private val failWrites: Boolean = false,
    ) : FirstInstallMarkerStore {
        val writes = mutableListOf<FirstInstallMarkerPhase>()
        override fun read(): FirstInstallMarkerRead = read
        override fun write(phase: FirstInstallMarkerPhase) {
            if (failWrites) throw java.io.IOException("marker unavailable")
            writes += phase
            read = FirstInstallMarkerRead.Ready(phase)
        }
    }

    private class Panic(
        var read: PanicStoreReadResult =
            PanicStoreReadResult.Unavailable(PanicStoreFailure.MISSING),
    ) : FirstInstallPanicPort {
        var initializeCalls = 0

        override suspend fun read(): PanicStoreReadResult = read

        override suspend fun initializeFresh(): PanicInitializationResult {
            initializeCalls += 1
            return if (
                read == PanicStoreReadResult.Unavailable(PanicStoreFailure.MISSING)
            ) {
                read = PanicStoreReadResult.Ready(PanicPersistentState.initial())
                PanicInitializationResult.Created
            } else {
                PanicInitializationResult.AlreadyInitialized
            }
        }
    }

    @Test
    fun cleanFreshInstallCommitsBeginBeforePanicAndThenComplete() = runTest {
        val marker = Marker()
        val panic = Panic()
        val ceremony = FirstInstallSecurityCeremony(
            marker,
            panic,
            SecurityFootprintProbe { SecurityFootprintRead.CLEAN },
        )

        assertEquals(FirstInstallSecurityStatus.Eligible, ceremony.status())
        assertEquals(FirstInstallSecurityStatus.Ready, ceremony.initialize())
        assertEquals(
            listOf(
                FirstInstallMarkerPhase.BEGIN,
                FirstInstallMarkerPhase.COMPLETE,
            ),
            marker.writes,
        )
        assertEquals(1, panic.initializeCalls)
    }

    @Test
    fun missingPanicAfterCompletedMarkerNeverReinitializes() = runTest {
        val marker = Marker(
            FirstInstallMarkerRead.Ready(FirstInstallMarkerPhase.COMPLETE)
        )
        val panic = Panic()
        val ceremony = FirstInstallSecurityCeremony(
            marker,
            panic,
            SecurityFootprintProbe { SecurityFootprintRead.CLEAN },
        )

        val status = ceremony.initialize()
        assertTrue(status is FirstInstallSecurityStatus.Blocked)
        assertEquals(0, panic.initializeCalls)
        assertTrue(marker.writes.isEmpty())
    }

    @Test
    fun anyPriorSecurityFootprintBlocksFreshInitialization() = runTest {
        val marker = Marker()
        val panic = Panic()
        val ceremony = FirstInstallSecurityCeremony(
            marker,
            panic,
            SecurityFootprintProbe { SecurityFootprintRead.PRESENT },
        )

        val status = ceremony.initialize()
        assertEquals(
            FirstInstallSecurityStatus.Blocked(
                FirstInstallSecurityStatus.Reason.SECURITY_FOOTPRINT_PRESENT
            ),
            status,
        )
        assertEquals(0, panic.initializeCalls)
        assertTrue(marker.writes.isEmpty())
    }

    @Test
    fun interruptedBeginCanResumeBeforeOrAfterPanicCreation() = runTest {
        val markerBefore = Marker(
            FirstInstallMarkerRead.Ready(FirstInstallMarkerPhase.BEGIN)
        )
        val panicBefore = Panic()
        val before = FirstInstallSecurityCeremony(
            markerBefore,
            panicBefore,
            SecurityFootprintProbe { SecurityFootprintRead.PRESENT },
        )
        assertEquals(FirstInstallSecurityStatus.Interrupted, before.status())
        assertEquals(FirstInstallSecurityStatus.Ready, before.initialize())
        assertEquals(1, panicBefore.initializeCalls)

        val markerAfter = Marker(
            FirstInstallMarkerRead.Ready(FirstInstallMarkerPhase.BEGIN)
        )
        val panicAfter = Panic(
            PanicStoreReadResult.Ready(PanicPersistentState.initial())
        )
        val after = FirstInstallSecurityCeremony(
            markerAfter,
            panicAfter,
            SecurityFootprintProbe { SecurityFootprintRead.PRESENT },
        )
        assertEquals(FirstInstallSecurityStatus.Interrupted, after.status())
        assertEquals(FirstInstallSecurityStatus.Ready, after.initialize())
        assertEquals(0, panicAfter.initializeCalls)
        assertEquals(
            listOf(FirstInstallMarkerPhase.COMPLETE),
            markerAfter.writes,
        )
    }

    @Test
    fun interruptedCeremonyRefusesAChangedPanicState() = runTest {
        val changed = PanicPersistentState(
            phase = PanicPhase.LOCAL_PENDING,
            panicIdHex = "11".repeat(32),
        )
        val marker = Marker(
            FirstInstallMarkerRead.Ready(FirstInstallMarkerPhase.BEGIN)
        )
        val panic = Panic(PanicStoreReadResult.Ready(changed))
        val ceremony = FirstInstallSecurityCeremony(
            marker,
            panic,
            SecurityFootprintProbe { SecurityFootprintRead.CLEAN },
        )

        assertEquals(
            FirstInstallSecurityStatus.Blocked(
                FirstInstallSecurityStatus.Reason.PANIC_CHANGED_DURING_CEREMONY
            ),
            ceremony.initialize(),
        )
        assertEquals(0, panic.initializeCalls)
    }

    @Test
    fun validLegacyPanicStateIsOnlySealedNeverReset() = runTest {
        val existing = PanicPersistentState.initial()
        val marker = Marker()
        val panic = Panic(PanicStoreReadResult.Ready(existing))
        val ceremony = FirstInstallSecurityCeremony(
            marker,
            panic,
            SecurityFootprintProbe { SecurityFootprintRead.PRESENT },
        )

        assertEquals(FirstInstallSecurityStatus.NeedsMarkerSeal, ceremony.status())
        assertEquals(FirstInstallSecurityStatus.Ready, ceremony.initialize())
        assertEquals(0, panic.initializeCalls)
        assertEquals(
            listOf(FirstInstallMarkerPhase.COMPLETE),
            marker.writes,
        )
    }

    @Test
    fun corruptMarkerBlocksWithoutTouchingPanic() = runTest {
        val marker = Marker(FirstInstallMarkerRead.Unavailable)
        val panic = Panic()
        val ceremony = FirstInstallSecurityCeremony(
            marker,
            panic,
            SecurityFootprintProbe { SecurityFootprintRead.CLEAN },
        )

        assertEquals(
            FirstInstallSecurityStatus.Blocked(
                FirstInstallSecurityStatus.Reason.MARKER_UNAVAILABLE
            ),
            ceremony.initialize(),
        )
        assertEquals(0, panic.initializeCalls)
    }

    @Test
    fun markerWriteFailureBlocksBeforePanicInitialization() = runTest {
        val marker = Marker(failWrites = true)
        val panic = Panic()
        val ceremony = FirstInstallSecurityCeremony(
            marker,
            panic,
            SecurityFootprintProbe { SecurityFootprintRead.CLEAN },
        )

        assertEquals(
            FirstInstallSecurityStatus.Blocked(
                FirstInstallSecurityStatus.Reason.MARKER_UNAVAILABLE
            ),
            ceremony.initialize(),
        )
        assertEquals(0, panic.initializeCalls)
    }

    @Test
    fun markerCodecRejectsCorruption() {
        val encoded = FirstInstallMarkerCodec.encode(FirstInstallMarkerPhase.BEGIN)
        assertEquals(
            FirstInstallMarkerPhase.BEGIN,
            FirstInstallMarkerCodec.decode(encoded),
        )
        val corrupt = encoded.copyOf()
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        var failed = false
        try {
            FirstInstallMarkerCodec.decode(corrupt)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }
}
