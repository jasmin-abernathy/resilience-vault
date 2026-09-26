package org.lepotager.resiliencevault.cloud

import java.io.FileNotFoundException
import java.io.IOException

internal abstract class RemoteDeleteProvisioningTestFixture {
    protected val identity = RemoteDeleteProvisioningIdentity(
        serviceId = "test-primary",
        tenantId = "tenant-1",
        vaultIdHex = "1".repeat(64),
        vaultGenerationHex = "2".repeat(64),
    )

    protected fun prepared() = RemoteDeleteProvisioningJournal.prepared(
        operationIdHex = "3".repeat(64),
        identity = identity,
        authorityRevisionAtBegin = 7,
        requestDigestHex = "4".repeat(64),
        capsuleDigestHex = "5".repeat(64),
    )

    protected fun provisionedBinding(journal: RemoteDeleteProvisioningJournal) =
        RemoteDeleteProvisioningBinding(
            operationIdHex = journal.operationIdHex,
            identity = journal.identity,
            requestDigestHex = journal.requestDigestHex,
        )

    protected fun fullyCapsuleVerified(): RemoteDeleteProvisioningJournal {
        val prepared = prepared()
        val confirmed = (
            RemoteDeleteProvisioningStateMachine.observeServer(
                prepared,
                RemoteDeleteProvisioningServerObservation.Confirmed(
                    provisionedBinding(prepared),
                    RemoteDeleteProvisioningServerState.PROVISIONED,
                ),
            ) as RemoteDeleteProvisioningDecision.Advance
        ).journal
        return (
            RemoteDeleteProvisioningStateMachine.verifyCapsule(
                confirmed,
                RemoteDeleteProvisioningCapsuleEvidence(
                    identity = identity,
                    capsuleDigestHex = confirmed.capsuleDigestHex,
                    capsuleReadable = true,
                    deleteKekPresent = true,
                ),
            ) as RemoteDeleteProvisioningDecision.Advance
        ).journal
    }

    protected class FakeFile : RemoteDeleteProvisioningJournalFile {
        var bytes: ByteArray? = null
        var failWrite = false
        var ignoreWrites = false
        var throwAfterWrite = false

        override fun read(): ByteArray =
            bytes?.copyOf() ?: throw FileNotFoundException()

        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("simulated")
            if (!ignoreWrites) this.bytes = bytes.copyOf()
            if (throwAfterWrite) throw IOException("simulated after write")
        }
    }
}
