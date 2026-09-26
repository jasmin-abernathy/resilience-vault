package org.lepotager.resiliencevault.cloud

import java.io.FileNotFoundException

internal interface RemoteDeleteProvisioningJournalFile {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}

internal sealed interface RemoteDeleteProvisioningJournalRead {
    data object Missing : RemoteDeleteProvisioningJournalRead
    data class Ready(val journal: RemoteDeleteProvisioningJournal) :
        RemoteDeleteProvisioningJournalRead
    data object Unavailable : RemoteDeleteProvisioningJournalRead
}

internal class VerifiedRemoteDeleteProvisioningJournalStore(
    private val file: RemoteDeleteProvisioningJournalFile,
) {
    private var failed = false

    @Synchronized
    fun read(): RemoteDeleteProvisioningJournalRead {
        if (failed) return RemoteDeleteProvisioningJournalRead.Unavailable
        return try {
            RemoteDeleteProvisioningJournalRead.Ready(
                RemoteDeleteProvisioningJournalCodec.decode(file.read())
            )
        } catch (_: FileNotFoundException) {
            RemoteDeleteProvisioningJournalRead.Missing
        } catch (_: Exception) {
            failed = true
            RemoteDeleteProvisioningJournalRead.Unavailable
        }
    }

    @Synchronized
    fun createPrepared(journal: RemoteDeleteProvisioningJournal) {
        require(journal.phase == RemoteDeleteProvisioningPhase.PREPARED)
        require(
            journal.lastConfirmedServerState ==
                RemoteDeleteProvisioningServerState.UNCONFIRMED
        )
        check(read() == RemoteDeleteProvisioningJournalRead.Missing) {
            "Provisioning journal is not fresh"
        }
        commit(journal)
    }

    @Synchronized
    fun replaceExpected(
        expected: RemoteDeleteProvisioningJournal,
        next: RemoteDeleteProvisioningJournal,
    ) {
        check(read() == RemoteDeleteProvisioningJournalRead.Ready(expected)) {
            "Provisioning journal changed"
        }
        next.validateTransitionFrom(expected)
        commit(next)
    }

    private fun commit(journal: RemoteDeleteProvisioningJournal) {
        check(!failed)
        try {
            val bytes = RemoteDeleteProvisioningJournalCodec.encode(journal)
            file.write(bytes)
            check(read() == RemoteDeleteProvisioningJournalRead.Ready(journal)) {
                "Provisioning journal verification failed"
            }
        } catch (error: Exception) {
            failed = true
            throw error
        }
    }
}
