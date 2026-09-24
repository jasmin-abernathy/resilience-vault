package org.lepotager.resiliencevault.panic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.lepotager.resiliencevault.crypto.TinkVaultSession
import org.lepotager.resiliencevault.crypto.VaultCryptoRuntime
import org.junit.Assert.*
import org.junit.Test

class VaultCryptoRuntimeTest {
    @Test fun offlinePanicInvalidatesActualSessionAndCancelsLeaseBeforeKeyDeletion() = runTest {
        val manager = VaultAccessLeaseManager(InMemoryPanicStateStore())
        val calls = mutableListOf<String>()
        val runtime = VaultCryptoRuntime(manager, { calls += "aliases" }, { calls += "credentials" })
        val entered = CompletableDeferred<TinkVaultSession>()
        val active = async {
            runtime.useSession(VaultAccessOperation.READ,
                { TinkVaultSession.create("11".repeat(32), "22".repeat(32), 1) }) {
                entered.complete(it)
                awaitCancellation()
            }
        }
        val handle = entered.await()
        assertEquals(PanicEffectResult.COMPLETED, runtime.invalidateInFlightAccess())
        active.join()
        assertTrue(active.isCancelled)
        assertThrows(Exception::class.java) { handle.checkLive() }
        assertEquals(PanicEffectResult.COMPLETED, runtime.destroyLocalReadCapability())
        assertEquals(listOf("aliases", "credentials"), calls)
        assertTrue(runtime.useSession(VaultAccessOperation.RESTORE,
            { error("must not open") }) { Unit } is VaultLeaseExecution.Blocked)
    }
    @Test fun keyDeletionExceptionNeverMeansSuccess() = runTest {
        val runtime = VaultCryptoRuntime(VaultAccessLeaseManager(InMemoryPanicStateStore()),
            { throw java.io.IOException("Keystore unavailable") }, { error("must not continue") })
        runtime.invalidateInFlightAccess()
        var failed = false
        try { runtime.destroyLocalReadCapability() } catch (_: java.io.IOException) { failed = true }
        assertTrue(failed)
    }
}
