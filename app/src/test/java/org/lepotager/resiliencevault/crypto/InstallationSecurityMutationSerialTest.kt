package org.lepotager.resiliencevault.crypto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationSecurityMutationSerialTest {
    @Test
    fun second_operation_cannot_observe_mid_transition_state() = runTest {
        val serial = InstallationSecurityMutationSerial()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var state = "READY"

        val first = async {
            serial.withLock {
                state = "BLOCKED_TRANSITION"
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = async {
            serial.withLock {
                secondEntered.complete(Unit)
                state
            }
        }

        runCurrent()
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        assertEquals("BLOCKED_TRANSITION", second.await())
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun cancellation_releases_lock_without_sleep() = runTest {
        val serial = InstallationSecurityMutationSerial()
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()

        val first = async {
            serial.withLock {
                entered.complete(Unit)
                never.await()
            }
        }
        entered.await()
        first.cancel()
        first.join()

        val result = async {
            serial.withLock { "released" }
        }
        runCurrent()
        assertEquals("released", result.await())
    }
}
