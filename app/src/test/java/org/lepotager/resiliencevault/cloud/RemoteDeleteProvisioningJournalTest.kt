package org.lepotager.resiliencevault.cloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RemoteDeleteProvisioningJournalTest : RemoteDeleteProvisioningTestFixture() {
    @Test
    fun sensitive_contract_string_representations_are_redacted() {
        val journal = prepared()
        val binding = provisionedBinding(journal)
        val confirmed = RemoteDeleteProvisioningServerObservation.Confirmed(
            binding,
            RemoteDeleteProvisioningServerState.PROVISIONED,
        )
        val conflict = RemoteDeleteProvisioningServerObservation.Conflict(
            journal.operationIdHex,
            journal.requestDigestHex,
            "6".repeat(64),
        )

        for (value in listOf(journal.identity, journal, binding, confirmed, conflict)) {
            val rendered = value.toString()
            assertTrue(!rendered.contains(identity.tenantId))
            assertTrue(!rendered.contains(identity.vaultIdHex))
            assertTrue(!rendered.contains(journal.operationIdHex))
            assertTrue(!rendered.contains(journal.requestDigestHex))
        }
    }

    @Test
    fun codec_round_trip_and_corruption_are_strict() {
        val journal = prepared()
        val encoded = RemoteDeleteProvisioningJournalCodec.encode(journal)
        assertEquals(journal, RemoteDeleteProvisioningJournalCodec.decode(encoded))

        val corrupt = encoded.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteDeleteProvisioningJournalCodec.decode(corrupt)
        }
    }

    @Test
    fun store_write_failure_latches_unavailable_and_never_infers_success() {
        val file = FakeFile().apply { failWrite = true }
        val store = VerifiedRemoteDeleteProvisioningJournalStore(file)

        assertThrows(IOException::class.java) {
            store.createPrepared(prepared())
        }
        assertEquals(RemoteDeleteProvisioningJournalRead.Unavailable, store.read())
    }

    @Test
    fun store_readback_mismatch_latches_unavailable() {
        val file = FakeFile().apply { ignoreWrites = true }
        val store = VerifiedRemoteDeleteProvisioningJournalStore(file)

        assertThrows(IllegalStateException::class.java) {
            store.createPrepared(prepared())
        }
        assertEquals(RemoteDeleteProvisioningJournalRead.Unavailable, store.read())
    }

    @Test
    fun store_rejects_non_monotonic_transition() {
        val file = FakeFile()
        val store = VerifiedRemoteDeleteProvisioningJournalStore(file)
        val prepared = prepared()
        store.createPrepared(prepared)

        assertThrows(IllegalArgumentException::class.java) {
            store.replaceExpected(
                prepared,
                prepared.copy(
                    phase = RemoteDeleteProvisioningPhase.CAPSULE_VERIFIED,
                    lastConfirmedServerState = RemoteDeleteProvisioningServerState.PROVISIONED,
                ),
            )
        }
    }

    @Test
    fun uncertain_commit_after_each_phase_never_reports_success_before_restart() {
        val file = FakeFile()
        var store = VerifiedRemoteDeleteProvisioningJournalStore(file)
        val prepared = prepared()

        file.throwAfterWrite = true
        assertThrows(IOException::class.java) { store.createPrepared(prepared) }
        assertEquals(RemoteDeleteProvisioningJournalRead.Unavailable, store.read())

        file.throwAfterWrite = false
        store = VerifiedRemoteDeleteProvisioningJournalStore(file)
        assertEquals(RemoteDeleteProvisioningJournalRead.Ready(prepared), store.read())

        val confirmed = (
            RemoteDeleteProvisioningStateMachine.observeServer(
                prepared,
                RemoteDeleteProvisioningServerObservation.Confirmed(
                    provisionedBinding(prepared),
                    RemoteDeleteProvisioningServerState.PROVISIONED,
                ),
            ) as RemoteDeleteProvisioningDecision.Advance
        ).journal
        val verified = (
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
        val published = (
            RemoteDeleteProvisioningStateMachine.publishAuthority(
                verified,
                RemoteDeleteProvisioningAuthorityEvidence(
                    identity = identity,
                    blockedAuthorityRevision = verified.authorityRevisionAtBegin + 1,
                    blockedTransition = true,
                    panicIdle = true,
                    armAbsent = true,
                ),
            ) as RemoteDeleteProvisioningDecision.Advance
        ).journal

        for ((expected, next) in listOf(
            prepared to confirmed,
            confirmed to verified,
            verified to published,
        )) {
            file.bytes = RemoteDeleteProvisioningJournalCodec.encode(expected)
            file.throwAfterWrite = true
            store = VerifiedRemoteDeleteProvisioningJournalStore(file)
            assertThrows(IOException::class.java) {
                store.replaceExpected(expected, next)
            }
            assertEquals(RemoteDeleteProvisioningJournalRead.Unavailable, store.read())

            file.throwAfterWrite = false
            store = VerifiedRemoteDeleteProvisioningJournalStore(file)
            assertEquals(RemoteDeleteProvisioningJournalRead.Ready(next), store.read())
        }
    }

    @Test
    fun failed_write_before_each_phase_preserves_previous_state_on_restart() {
        val file = FakeFile()
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

        file.bytes = RemoteDeleteProvisioningJournalCodec.encode(prepared)
        file.failWrite = true
        var store = VerifiedRemoteDeleteProvisioningJournalStore(file)
        assertThrows(IOException::class.java) {
            store.replaceExpected(prepared, confirmed)
        }
        assertEquals(RemoteDeleteProvisioningJournalRead.Unavailable, store.read())

        file.failWrite = false
        store = VerifiedRemoteDeleteProvisioningJournalStore(file)
        assertEquals(RemoteDeleteProvisioningJournalRead.Ready(prepared), store.read())
    }
}
