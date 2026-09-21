package org.lepotager.resiliencevault.panic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultAccessLeaseManagerTest {
    @Test
    fun registry_returns_same_manager_for_same_store_instance() {
        val store = InMemoryPanicStateStore()
        assertSame(
            VaultAccessLeaseManagers.forStore(store),
            VaultAccessLeaseManagers.forStore(store)
        )
    }

    @Test
    fun missing_or_panicking_store_blocks_new_access() = runTest {
        val missing = VaultAccessLeaseManager(InMemoryPanicStateStore(initial = null))
        val missingResult = missing.withLease(VaultAccessOperation.READ) { "should-not-run" }
        assertEquals(
            VaultAccessBlockReason.STORE_UNAVAILABLE,
            (missingResult as VaultLeaseExecution.Blocked).reason
        )

        val panicking = VaultAccessLeaseManager(
            InMemoryPanicStateStore(
                PanicPersistentState(
                    phase = PanicPhase.LOCAL_PENDING,
                    panicIdHex = "d".repeat(64)
                )
            )
        )
        val panicResult = panicking.withLease(VaultAccessOperation.EXPORT) { "should-not-run" }
        assertEquals(
            VaultAccessBlockReason.PANIC_IN_PROGRESS,
            (panicResult as VaultLeaseExecution.Blocked).reason
        )
    }

    @Test
    fun panic_drain_waits_for_existing_lease_and_blocks_new_ones() = runTest {
        val manager = VaultAccessLeaseManager(InMemoryPanicStateStore())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val active = async {
            manager.withLease(VaultAccessOperation.READ) {
                entered.complete(Unit)
                release.await()
                "finished"
            }
        }
        entered.await()

        val drain = async { manager.closeAndDrainForPanic() }
        runCurrent()
        assertFalse(drain.isCompleted)

        val blocked = manager.withLease(VaultAccessOperation.EXPORT) { "should-not-run" }
        assertEquals(
            VaultAccessBlockReason.PANIC_IN_PROGRESS,
            (blocked as VaultLeaseExecution.Blocked).reason
        )

        release.complete(Unit)
        assertEquals(PanicEffectResult.COMPLETED, drain.await())
        assertEquals(
            "finished",
            (active.await() as VaultLeaseExecution.Completed).value
        )
    }

    @Test
    fun cancelled_operation_releases_lease() = runTest {
        val manager = VaultAccessLeaseManager(InMemoryPanicStateStore())
        val entered = CompletableDeferred<Unit>()

        val active = async {
            manager.withLease(VaultAccessOperation.SYNC) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        active.cancelAndJoin()

        assertEquals(PanicEffectResult.COMPLETED, manager.closeAndDrainForPanic())
    }

    @Test
    fun drain_is_irreversible_for_process_lifetime() = runTest {
        val manager = VaultAccessLeaseManager(InMemoryPanicStateStore())
        assertEquals(PanicEffectResult.COMPLETED, manager.closeAndDrainForPanic())

        val result = manager.withLease(VaultAccessOperation.RESTORE) { "should-not-run" }
        assertTrue(result is VaultLeaseExecution.Blocked)
        assertEquals(
            VaultAccessBlockReason.PANIC_IN_PROGRESS,
            (result as VaultLeaseExecution.Blocked).reason
        )
    }
}
