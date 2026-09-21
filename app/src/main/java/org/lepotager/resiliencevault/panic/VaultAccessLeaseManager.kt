package org.lepotager.resiliencevault.panic

import java.util.IdentityHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VaultAccessOperation {
    READ,
    EXPORT,
    SYNC,
    RESTORE
}

sealed interface VaultLeaseExecution<out T> {
    data class Completed<T>(val value: T) : VaultLeaseExecution<T>
    data class Blocked(val reason: VaultAccessBlockReason) : VaultLeaseExecution<Nothing>
}

/**
 * Process-local serialization point for every operation that can obtain a usable vault handle.
 *
 * A panic closes this manager permanently for the process, then waits for all already-acquired
 * leases to release. The persistent panic store remains the durable authority across restarts.
 */
class VaultAccessLeaseManager internal constructor(
    private val store: PanicStateStore
) {
    private val mutex = Mutex()
    private var closedForPanic = false
    private var activeLeases = 0
    private var drainWaiter: CompletableDeferred<Unit>? = null

    suspend fun <T> withLease(
        operation: VaultAccessOperation,
        block: suspend () -> T
    ): VaultLeaseExecution<T> {
        val blocked = acquire(operation)
        if (blocked != null) {
            return VaultLeaseExecution.Blocked(blocked)
        }

        return try {
            VaultLeaseExecution.Completed(block())
        } finally {
            release()
        }
    }

    suspend fun closeAndDrainForPanic(): PanicEffectResult {
        val waiter = mutex.withLock {
            closedForPanic = true
            if (activeLeases == 0) {
                null
            } else {
                drainWaiter ?: CompletableDeferred<Unit>().also { drainWaiter = it }
            }
        }

        waiter?.await()
        return PanicEffectResult.COMPLETED
    }

    private suspend fun acquire(
        operation: VaultAccessOperation
    ): VaultAccessBlockReason? = mutex.withLock {
        @Suppress("UNUSED_VARIABLE")
        val operationForAudit = operation

        if (closedForPanic) {
            return@withLock VaultAccessBlockReason.PANIC_IN_PROGRESS
        }

        when (val read = store.read()) {
            is PanicStoreReadResult.Unavailable ->
                VaultAccessBlockReason.STORE_UNAVAILABLE

            is PanicStoreReadResult.Ready -> {
                if (read.state.phase != PanicPhase.IDLE) {
                    VaultAccessBlockReason.PANIC_IN_PROGRESS
                } else {
                    activeLeases += 1
                    null
                }
            }
        }
    }

    private suspend fun release() {
        mutex.withLock {
            check(activeLeases > 0) { "Vault access lease underflow" }
            activeLeases -= 1
            if (activeLeases == 0) {
                drainWaiter?.complete(Unit)
                drainWaiter = null
            }
        }
    }
}

/**
 * Returns one lease manager per PanicStateStore object identity in this process.
 *
 * AtomicFilePanicStateStore itself is singleton-per-path, so ordinary app wiring converges on
 * one lease manager. A multi-process manifest would require a different locking protocol.
 */
object VaultAccessLeaseManagers {
    private val managers = IdentityHashMap<PanicStateStore, VaultAccessLeaseManager>()

    @Synchronized
    fun forStore(store: PanicStateStore): VaultAccessLeaseManager =
        managers[store] ?: VaultAccessLeaseManager(store).also { managers[store] = it }
}

class LeaseDrainingLocalCriticalEffects(
    private val leaseManager: VaultAccessLeaseManager,
    private val keyDestroyer: VaultReadCapabilityDestroyer
) : LocalCriticalPanicEffects {
    override suspend fun invalidateInFlightAccess(): PanicEffectResult =
        leaseManager.closeAndDrainForPanic()

    override suspend fun destroyLocalReadCapability(): PanicEffectResult =
        keyDestroyer.destroyLocalReadCapability()
}

fun interface VaultReadCapabilityDestroyer {
    suspend fun destroyLocalReadCapability(): PanicEffectResult
}
