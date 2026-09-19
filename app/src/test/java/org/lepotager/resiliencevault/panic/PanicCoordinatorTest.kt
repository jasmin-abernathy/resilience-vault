package org.lepotager.resiliencevault.panic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanicCoordinatorTest {
    @Test
    fun local_keys_are_destroyed_before_remote_delete() = runTest {
        val fake = RecordingPanicActions(true)
        val report = PanicCoordinator(fake).execute()
        assertTrue(fake.calls.indexOf(PanicStep.REQUEST_REMOTE_DELETE) >
            fake.calls.indexOf(PanicStep.DESTROY_LOCAL_KEYS))
        assertFalse(report.remoteDeletePending)
    }

    @Test
    fun failed_remote_delete_is_queued_after_local_destruction() = runTest {
        val fake = RecordingPanicActions(false)
        val report = PanicCoordinator(fake).execute()
        assertTrue(fake.calls.indexOf(PanicStep.QUEUE_DELETE_RETRY) >
            fake.calls.indexOf(PanicStep.DESTROY_LOCAL_KEYS))
        assertTrue(report.remoteDeletePending)
    }
}

private class RecordingPanicActions(private val remoteDeleteSucceeds: Boolean) : PanicActions {
    val calls = mutableListOf<PanicStep>()
    override suspend fun lockInterface() { calls += PanicStep.LOCK_INTERFACE }
    override suspend fun destroyLocalKeys() { calls += PanicStep.DESTROY_LOCAL_KEYS }
    override suspend fun purgeLocalStaging() { calls += PanicStep.PURGE_LOCAL_STAGING }
    override suspend fun revokeConnectorSessions() { calls += PanicStep.REVOKE_CONNECTOR_SESSIONS }
    override suspend fun requestRemoteDelete(): Boolean {
        calls += PanicStep.REQUEST_REMOTE_DELETE
        return remoteDeleteSucceeds
    }
    override suspend fun queueRemoteDeleteRetry() { calls += PanicStep.QUEUE_DELETE_RETRY }
    override suspend fun disableLauncher() { calls += PanicStep.DISABLE_LAUNCHER }
}
