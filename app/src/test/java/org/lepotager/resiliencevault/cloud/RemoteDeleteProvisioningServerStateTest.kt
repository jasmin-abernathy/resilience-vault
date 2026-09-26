package org.lepotager.resiliencevault.cloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RemoteDeleteProvisioningServerStateTest : RemoteDeleteProvisioningTestFixture() {
    @Test
    fun timeout_is_unknown_and_keeps_prepared_without_marking_failure() {
        val journal = prepared()
        val decision = RemoteDeleteProvisioningStateMachine.observeServer(
            journal,
            RemoteDeleteProvisioningServerObservation.Unknown(
                RemoteDeleteProvisioningUnknownReason.TIMEOUT
            ),
        )
        assertEquals(RemoteDeleteProvisioningDecision.Keep(journal), decision)
    }

    @Test
    fun replayed_same_operation_and_digest_is_idempotent() {
        val prepared = prepared()
        val observation = RemoteDeleteProvisioningServerObservation.Confirmed(
            provisionedBinding(prepared),
            RemoteDeleteProvisioningServerState.PROVISIONED,
        )
        val first = RemoteDeleteProvisioningStateMachine.observeServer(
            prepared,
            observation,
        ) as RemoteDeleteProvisioningDecision.Advance
        assertEquals(RemoteDeleteProvisioningPhase.SERVER_CONFIRMED, first.journal.phase)

        val replay = RemoteDeleteProvisioningStateMachine.observeServer(
            first.journal,
            observation,
        )
        assertEquals(RemoteDeleteProvisioningDecision.Keep(first.journal), replay)
    }

    @Test
    fun same_operation_with_different_digest_is_definitive_conflict() {
        val journal = prepared()
        val conflict = RemoteDeleteProvisioningStateMachine.observeServer(
            journal,
            RemoteDeleteProvisioningServerObservation.Conflict(
                operationIdHex = journal.operationIdHex,
                expectedRequestDigestHex = journal.requestDigestHex,
                observedRequestDigestHex = "6".repeat(64),
            ),
        ) as RemoteDeleteProvisioningDecision.Advance

        assertEquals(
            RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION,
            conflict.journal.phase,
        )
        assertEquals(
            RemoteDeleteProvisioningServerState.CONFLICT,
            conflict.journal.lastConfirmedServerState,
        )
    }

    @Test
    fun lost_response_can_be_reconciled_by_exact_status_observation() {
        val journal = prepared()
        val unknown = RemoteDeleteProvisioningStateMachine.observeServer(
            journal,
            RemoteDeleteProvisioningServerObservation.Unknown(
                RemoteDeleteProvisioningUnknownReason.NO_NETWORK
            ),
        ) as RemoteDeleteProvisioningDecision.Keep
        assertEquals(journal, unknown.journal)

        val confirmed = RemoteDeleteProvisioningStateMachine.observeServer(
            journal,
            RemoteDeleteProvisioningServerObservation.Confirmed(
                provisionedBinding(journal),
                RemoteDeleteProvisioningServerState.PROVISIONED,
            ),
        ) as RemoteDeleteProvisioningDecision.Advance
        assertEquals(
            RemoteDeleteProvisioningPhase.SERVER_CONFIRMED,
            confirmed.journal.phase,
        )
    }

    @Test
    fun abandoned_provisioning_can_record_later_confirmed_revocation_without_reopening() {
        val journal = prepared()
        val abandoned = (
            RemoteDeleteProvisioningStateMachine.abandonForReconciliation(journal)
                as RemoteDeleteProvisioningDecision.Advance
        ).journal

        val revoked = RemoteDeleteProvisioningStateMachine.observeServer(
            abandoned,
            RemoteDeleteProvisioningServerObservation.Confirmed(
                provisionedBinding(journal),
                RemoteDeleteProvisioningServerState.REVOKED,
            ),
        ) as RemoteDeleteProvisioningDecision.Advance
        assertEquals(
            RemoteDeleteProvisioningPhase.ABANDONED_NEEDS_RECONCILIATION,
            revoked.journal.phase,
        )
        assertEquals(
            RemoteDeleteProvisioningServerState.REVOKED,
            revoked.journal.lastConfirmedServerState,
        )
    }

    @Test
    fun conflict_after_server_confirmation_is_contradictory_and_blocked() {
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

        val conflict = RemoteDeleteProvisioningStateMachine.observeServer(
            confirmed,
            RemoteDeleteProvisioningServerObservation.Conflict(
                operationIdHex = confirmed.operationIdHex,
                expectedRequestDigestHex = confirmed.requestDigestHex,
                observedRequestDigestHex = "8".repeat(64),
            ),
        ) as RemoteDeleteProvisioningDecision.Blocked

        assertEquals(
            RemoteDeleteProvisioningBlockReason.REQUEST_DIGEST_CONFLICT,
            conflict.reason,
        )
        assertEquals(confirmed, conflict.journal)
    }
}
