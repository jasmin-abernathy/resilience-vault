package org.lepotager.resiliencevault.panic

enum class PanicStep {
    LOCK_INTERFACE,
    DESTROY_LOCAL_KEYS,
    PURGE_LOCAL_STAGING,
    REVOKE_CONNECTOR_SESSIONS,
    REQUEST_REMOTE_DELETE,
    QUEUE_DELETE_RETRY,
    DISABLE_LAUNCHER
}

data class PanicReport(val completedSteps: List<PanicStep>, val remoteDeletePending: Boolean)

interface PanicActions {
    suspend fun lockInterface()
    suspend fun destroyLocalKeys()
    suspend fun purgeLocalStaging()
    suspend fun revokeConnectorSessions()
    suspend fun requestRemoteDelete(): Boolean
    suspend fun queueRemoteDeleteRetry()
    suspend fun disableLauncher()
}

class PanicCoordinator(private val actions: PanicActions) {
    suspend fun execute(): PanicReport {
        val completed = mutableListOf<PanicStep>()
        actions.lockInterface(); completed += PanicStep.LOCK_INTERFACE
        actions.destroyLocalKeys(); completed += PanicStep.DESTROY_LOCAL_KEYS
        actions.purgeLocalStaging(); completed += PanicStep.PURGE_LOCAL_STAGING
        actions.revokeConnectorSessions(); completed += PanicStep.REVOKE_CONNECTOR_SESSIONS
        val remoteDeleted = actions.requestRemoteDelete()
        completed += PanicStep.REQUEST_REMOTE_DELETE
        if (!remoteDeleted) {
            actions.queueRemoteDeleteRetry()
            completed += PanicStep.QUEUE_DELETE_RETRY
        }
        actions.disableLauncher(); completed += PanicStep.DISABLE_LAUNCHER
        return PanicReport(completed.toList(), !remoteDeleted)
    }
}
