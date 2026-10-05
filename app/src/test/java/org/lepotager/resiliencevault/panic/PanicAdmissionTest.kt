package org.lepotager.resiliencevault.panic

import java.security.SecureRandom
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanicAdmissionTest {
    private val now = PanicClockSnapshot("boot-1", 100_000L, 1_000_000L)
    private var observation = PanicAdmissionObservation(now, true)
    private val contacts = (0 until 6).map { "+3360000000$it" }

    @Test
    fun contact_bounds_and_duration_allowlist_are_enforced() = runTest {
        val service = service()

        for (count in listOf(0, 1, 5, 6)) {
            val result = arm(service, request(contacts.take(count)))
            assertEquals(count in 1..5, result is RemoteArmResult.Armed)
        }

        for (hours in listOf(1L, 2L, 6L, 12L, 24L, 48L, 72L, 73L)) {
            val result = arm(service, request(listOf(contacts[0]), hours * 3_600_000L))
            assertEquals(hours in setOf(1L, 6L, 12L, 24L, 48L, 72L), result is RemoteArmResult.Armed)
        }
    }

    @Test
    fun generated_commands_are_distinct_and_secrets_are_not_persisted() = runTest {
        val store = knownNoCloudStore()
        val service = service(store)
        val result = arm(service, request(contacts.take(5))) as RemoteArmResult.Armed

        assertEquals(5, result.commands.map { it.command }.distinct().size)
        val state = (store.read() as PanicStoreReadResult.Ready).state
        val persisted = state.arm!!
        result.commands.forEach { command ->
            assertFalse(persisted.contacts.any { command.command.contains(it.verifierHex) })
            assertFalse(persisted.toString().contains(command.command.substringAfterLast(' ')))
        }
    }

    @Test
    fun valid_contact_consumes_entire_window_and_cross_contact_secret_fails() = runTest {
        val store = knownNoCloudStore()
        val service = service(store)
        val armed = arm(service, request(contacts.take(5))) as RemoteArmResult.Armed

        val cross = armed.commands[1].command
        val wrong = sms(service, envelope(contacts[0], cross))
        assertEquals(
            AdmissionRejectionReason.INVALID_SECRET,
            (wrong as AdmissionResult.Rejected).reason
        )

        val accepted = sms(service, envelope(contacts[3], armed.commands[3].command))
        assertTrue(accepted is AdmissionResult.Accepted)

        val state = (store.read() as PanicStoreReadResult.Ready).state
        assertNull(state.arm)
        assertEquals(PanicPhase.LOCAL_PENDING, state.phase)

        val replay = sms(service, envelope(contacts[3], armed.commands[3].command))
        assertEquals(
            AdmissionRejectionReason.PANIC_ACTIVE,
            (replay as AdmissionResult.Rejected).reason
        )
    }

    @Test
    fun exact_expiry_boot_and_clock_drift_fail_closed() = runTest {
        suspend fun attempt(clock: PanicClockSnapshot): AdmissionResult {
            observation = PanicAdmissionObservation(now, true)
            val store = knownNoCloudStore()
            val service = service(store)
            val armed = arm(service, request(listOf(contacts[0]))) as RemoteArmResult.Armed
            return sms(service, envelope(contacts[0], armed.commands.single().command, clock))
        }

        val almost = now.copy(
            elapsedRealtimeMs = now.elapsedRealtimeMs + 3_600_000L - 1,
            utcMs = now.utcMs + 3_600_000L - 1
        )
        assertTrue(attempt(almost) is AdmissionResult.Accepted)

        val boundary = now.copy(
            elapsedRealtimeMs = now.elapsedRealtimeMs + 3_600_000L,
            utcMs = now.utcMs + 3_600_000L
        )
        assertEquals(
            AdmissionRejectionReason.EXPIRED_OR_INVALIDATED,
            (attempt(boundary) as AdmissionResult.Rejected).reason
        )

        val reboot = now.copy(bootId = "boot-2")
        assertEquals(
            AdmissionRejectionReason.EXPIRED_OR_INVALIDATED,
            (attempt(reboot) as AdmissionResult.Rejected).reason
        )

        val drift = now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + 10, utcMs = now.utcMs + 3_000)
        assertEquals(
            AdmissionRejectionReason.EXPIRED_OR_INVALIDATED,
            (attempt(drift) as AdmissionResult.Rejected).reason
        )
    }

    @Test
    fun revoked_permission_disarms_and_untrusted_envelope_does_not_trigger() = runTest {
        val store = knownNoCloudStore()
        val service = service(store)
        val armed = arm(service, request(listOf(contacts[0]))) as RemoteArmResult.Armed

        val untrusted = sms(service, 
            envelope(contacts[0], armed.commands.single().command).copy(trustedSystemDelivery = false)
        )
        assertEquals(
            AdmissionRejectionReason.UNTRUSTED_ENVELOPE,
            (untrusted as AdmissionResult.Rejected).reason
        )

        observation = observation.copy(smsChannelReady = false)
        val revoked = sms(service, 
            ValidatedSmsEnvelope(contacts[0], armed.commands.single().command, true, true)
        )
        assertEquals(
            AdmissionRejectionReason.EXPIRED_OR_INVALIDATED,
            (revoked as AdmissionResult.Rejected).reason
        )
        val state = (store.read() as PanicStoreReadResult.Ready).state
        assertNull(state.arm)
    }

    @Test
    fun remove_contact_is_immediate_and_last_contact_disarms() = runTest {
        val store = knownNoCloudStore()
        val service = service(store)
        arm(service, request(contacts.take(2)))

        val removed = service.removeTrustedContact(contacts[0])
        assertTrue((removed as PanicTransactionResult.Success).value)
        var arm = (store.read() as PanicStoreReadResult.Ready).state.arm!!
        assertEquals(listOf(contacts[1]), arm.contacts.map { it.e164 })

        service.removeTrustedContact(contacts[1])
        assertNull((store.read() as PanicStoreReadResult.Ready).state.arm)
    }

    @Test
    fun local_panic_uses_same_persistent_intention_and_invalidates_remote_window() = runTest {
        val store = knownNoCloudStore()
        val service = service(store)
        arm(service, request(contacts.take(5)))

        assertTrue(service.acceptLocalVerified(AdmissionAuthoritySnapshot.notConfigured()) is AdmissionResult.Accepted)
        val state = (store.read() as PanicStoreReadResult.Ready).state
        assertEquals(PanicPhase.LOCAL_PENDING, state.phase)
        assertNull(state.arm)
        assertFalse(arm(service, request(listOf(contacts[0]))) is RemoteArmResult.Armed)
    }

    @Test
    fun unknown_remote_configuration_blocks_sms_arming_but_not_local_emergency() = runTest {
        val store = InMemoryPanicStateStore()
        val service = service(store)

        val armResult = service.armRemote(request(listOf(contacts[0])))
        assertEquals(
            ArmRejectionReason.REMOTE_DELETE_PROOF_REQUIRED,
            (armResult as RemoteArmResult.Rejected).reason,
        )

        assertTrue(service.acceptLocal() is AdmissionResult.Accepted)
        val state = (store.read() as PanicStoreReadResult.Ready).state
        assertEquals(PanicPhase.LOCAL_PENDING, state.phase)
        assertEquals(RemoteDeleteCheckpoint.LEGACY_UNPROVEN, state.remoteDeleteCheckpoint)
        assertTrue(state.legacyRemoteUnproven)
    }

    @Test
    fun commit_failure_never_reports_acceptance() = runTest {
        val store = knownNoCloudStore()
        val service = service(store)
        val armed = arm(service, request(listOf(contacts[0]))) as RemoteArmResult.Armed

        store.failNextCommit = true
        val result = sms(service, envelope(contacts[0], armed.commands.single().command))
        assertEquals(
            PanicStoreFailure.COMMIT_FAILED,
            (result as AdmissionResult.StorageUnavailable).failure
        )
        assertEquals(PanicPhase.IDLE, (store.read() as PanicStoreReadResult.Ready).state.phase)
    }

    @Test
    fun simultaneous_valid_contacts_have_exactly_one_winner() = runTest {
        val store = knownNoCloudStore()
        val service = service(store)
        val armed = arm(service, request(contacts.take(2))) as RemoteArmResult.Armed

        val first = async { sms(service, envelope(contacts[0], armed.commands[0].command)) }
        val second = async { sms(service, envelope(contacts[1], armed.commands[1].command)) }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it is AdmissionResult.Accepted })
        assertEquals(PanicPhase.LOCAL_PENDING, (store.read() as PanicStoreReadResult.Ready).state.phase)
    }

    @Test
    fun rearming_rotates_generation_and_all_commands() = runTest {
        val service = service()
        val first = arm(service, request(contacts.take(2))) as RemoteArmResult.Armed
        val second = arm(service, request(contacts.take(2))) as RemoteArmResult.Armed

        assertNotEquals(first.commands[0].command, second.commands[0].command)
        assertNotEquals(first.commands[1].command, second.commands[1].command)
    }

    private suspend fun arm(
        service: PanicAdmissionService,
        request: RemoteArmRequest,
    ): RemoteArmResult =
        service.armRemoteVerified(request, AdmissionAuthoritySnapshot.notConfigured())

    private suspend fun sms(
        service: PanicAdmissionService,
        envelope: ValidatedSmsEnvelope,
    ): AdmissionResult =
        service.acceptSmsVerified(envelope, AdmissionAuthoritySnapshot.notConfigured())

    private fun service(
        store: InMemoryPanicStateStore = knownNoCloudStore()
    ): PanicAdmissionService =
        PanicAdmissionService(
            store = store,
            environment = PanicAdmissionEnvironment { observation },
            tokenGenerator = RemotePanicTokenGenerator(SecureRandom())
        )

    private fun knownNoCloudStore(): InMemoryPanicStateStore =
        InMemoryPanicStateStore(
            PanicPersistentState.initial().copy(
                remoteDeleteConfiguration = RemoteDeleteConfiguration.NOT_CONFIGURED
            )
        )

    private fun request(
        numbers: List<String>,
        durationMs: Long = 3_600_000L
    ) = RemoteArmRequest(
        canonicalContactsE164 = numbers,
        durationMs = durationMs
    )

    private fun envelope(
        sender: String,
        body: String,
        clock: PanicClockSnapshot = now
    ): ValidatedSmsEnvelope {
        observation = PanicAdmissionObservation(clock, true)
        return ValidatedSmsEnvelope(sender, body, true, true)
    }
}
