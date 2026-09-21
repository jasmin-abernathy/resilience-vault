package org.lepotager.resiliencevault.panic

enum class VaultAccessBlockReason {
    STORE_UNAVAILABLE,
    PANIC_IN_PROGRESS
}

sealed interface VaultAccessDecision {
    data object Allowed : VaultAccessDecision
    data class Blocked(val reason: VaultAccessBlockReason) : VaultAccessDecision
}

/**
 * Snapshot/status gate only.
 *
 * Security-sensitive read/export/sync/restore operations must use VaultAccessLeaseManager
 * so acquisition is accounted for and the panic path can drain in-flight handles.
 */
class PanicAccessGate(
    private val store: PanicStateStore
) {
    suspend fun check(): VaultAccessDecision =
        when (val read = store.read()) {
            is PanicStoreReadResult.Unavailable ->
                VaultAccessDecision.Blocked(VaultAccessBlockReason.STORE_UNAVAILABLE)

            is PanicStoreReadResult.Ready ->
                if (read.state.phase == PanicPhase.IDLE) {
                    VaultAccessDecision.Allowed
                } else {
                    VaultAccessDecision.Blocked(VaultAccessBlockReason.PANIC_IN_PROGRESS)
                }
        }
}
