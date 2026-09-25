package org.lepotager.resiliencevault.crypto

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lepotager.resiliencevault.panic.LocalCriticalPanicEffects
import org.lepotager.resiliencevault.panic.PanicEffectResult
import org.lepotager.resiliencevault.panic.VaultAccessLeaseManager
import org.lepotager.resiliencevault.panic.VaultAccessOperation
import org.lepotager.resiliencevault.panic.VaultLeaseExecution
import java.util.Collections
import java.util.IdentityHashMap

/** Single owner for actual handles. Integration must use the singleton lease manager for its store. */
internal class VaultCryptoRuntime(
    private val leases: VaultAccessLeaseManager,
    private val destroyAliases: () -> Unit,
    private val removeReadCredentials: () -> Unit,
) : LocalCriticalPanicEffects {
    private val monitor = Any()
    private var closed = false
    private var drained = false
    private val operations = Mutex()
    private val sessions = Collections.newSetFromMap(IdentityHashMap<TinkVaultSession, Boolean>())

    suspend fun <T> useSession(operation: VaultAccessOperation, open: suspend () -> TinkVaultSession,
                              block: suspend (TinkVaultSession) -> T): VaultLeaseExecution<T> =
        leases.withLease(operation) {
          operations.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(monitor) { check(!closed) }
            val session = open()
            currentCoroutineContext().ensureActive()
            synchronized(monitor) {
                if (closed) {
                    session.close()
                    error("Panic closed admission during key opening")
                }
                sessions.add(session)
            }
            try {
                val value = block(session)
                currentCoroutineContext().ensureActive()
                synchronized(monitor) { check(!closed); session.checkLive() }
                value
            } finally {
                session.close()
                synchronized(monitor) { sessions.remove(session) }
            }
          }
        }

    override suspend fun invalidateInFlightAccess(): PanicEffectResult {
        // Invalidate immediately so bounded synchronous Tink loops also stop cooperatively.
        synchronized(monitor) {
            closed = true
            sessions.forEach { it.close() }
        }
        val result = leases.cancelAndDrainForPanic()
        if (result == PanicEffectResult.COMPLETED) synchronized(monitor) { drained = true }
        return result
    }
    override suspend fun destroyLocalReadCapability(): PanicEffectResult {
        synchronized(monitor) { check(closed && drained && sessions.isEmpty()) }
        destroyAliases() // Exceptions remain failures, never inferred absence.
        removeReadCredentials() // Separate from the optional DELETE-only capsule.
        return PanicEffectResult.COMPLETED
    }
}
