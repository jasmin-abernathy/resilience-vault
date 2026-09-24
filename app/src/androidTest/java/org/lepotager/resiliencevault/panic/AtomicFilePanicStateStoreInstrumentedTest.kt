package org.lepotager.resiliencevault.panic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicFilePanicStateStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val path: File
        get() = File(context.noBackupFilesDir, "security/remote-panic-state.bin")

    @Test
    fun localPanicPersistsBeforeCorruptionLatchesStoreClosed() = runBlocking {
        cleanup()
        path.parentFile!!.mkdirs()
        path.writeBytes(PanicStateCodec.encode(PanicPersistentState.initial()))

        val store = AtomicFilePanicStateStore.create(context)
        try {
            val initial = store.read()
            assertTrue(initial is PanicStoreReadResult.Ready)
            assertEquals(
                PanicPhase.IDLE,
                (initial as PanicStoreReadResult.Ready).state.phase
            )

            val admitted = PanicAdmissionService(store).acceptLocal()
            assertTrue(admitted is AdmissionResult.Accepted)

            val pending = store.read()
            assertTrue(pending is PanicStoreReadResult.Ready)
            assertEquals(
                PanicPhase.LOCAL_PENDING,
                (pending as PanicStoreReadResult.Ready).state.phase
            )
            assertTrue(
                path.canonicalPath.startsWith(
                    context.noBackupFilesDir.canonicalPath + File.separator
                )
            )

            // A corrupt durable panic record must close access rather than fall back to IDLE.
            path.writeBytes(byteArrayOf(0x52, 0x56, 0x50, 0x00))
            val corrupt = store.read()
            assertEquals(
                PanicStoreFailure.CORRUPT,
                (corrupt as PanicStoreReadResult.Unavailable).failure
            )

            val gate = PanicAccessGate(store).check()
            assertTrue(gate is VaultAccessDecision.Blocked)

            val retry = PanicAdmissionService(store).acceptLocal()
            assertEquals(
                PanicStoreFailure.CORRUPT,
                (retry as AdmissionResult.StorageUnavailable).failure
            )
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        path.delete()
        File(path.path + ".new").delete()
        File(path.path + ".bak").delete()
    }
}
