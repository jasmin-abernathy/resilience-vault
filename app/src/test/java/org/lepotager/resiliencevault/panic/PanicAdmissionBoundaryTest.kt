package org.lepotager.resiliencevault.panic

import java.security.SecureRandom
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PanicAdmissionBoundaryTest {
    private val start = PanicClockSnapshot("boot", 100L, 1_000L)
    private val request = RemoteArmRequest(listOf("+33600000000"), 3_600_000L)

    @Test
    fun expiration_while_waiting_for_transaction_is_not_bypassed_by_old_envelope() = runTest {
        val backing = InMemoryPanicStateStore()
        var observation = PanicAdmissionObservation(start, true)
        var beforeTransaction: () -> Unit = {}
        val store = object : PanicStateStore by backing {
            override suspend fun <T> transaction(transform: (PanicPersistentState) -> PanicStateMutation<T>): PanicTransactionResult<T> {
                beforeTransaction()
                return backing.transaction(transform)
            }
        }
        val service = PanicAdmissionService(store, PanicAdmissionEnvironment { observation })
        val armed = service.armRemote(request) as RemoteArmResult.Armed
        val envelope = ValidatedSmsEnvelope(request.canonicalContactsE164.single(), armed.commands.single().command, true, true)
        beforeTransaction = {
            observation = PanicAdmissionObservation(start.copy(
                elapsedRealtimeMs = start.elapsedRealtimeMs + request.durationMs,
                utcMs = start.utcMs + request.durationMs
            ), true)
        }
        val result = service.acceptSms(envelope) as AdmissionResult.Rejected
        assertEquals(AdmissionRejectionReason.EXPIRED_OR_INVALIDATED, result.reason)
        assertNull((store.read() as PanicStoreReadResult.Ready).state.arm)
    }

    @Test
    fun permission_lost_during_verification_is_rechecked() = runTest {
        val store = InMemoryPanicStateStore()
        var reads = 0
        val service = PanicAdmissionService(store, PanicAdmissionEnvironment {
            reads++
            PanicAdmissionObservation(start, reads < 4)
        })
        val armed = service.armRemote(request) as RemoteArmResult.Armed // observations 1 and 2
        val result = service.acceptSms(ValidatedSmsEnvelope(request.canonicalContactsE164.single(), armed.commands.single().command, true, true))
        assertTrue(result is AdmissionResult.Rejected)
        assertNull((store.read() as PanicStoreReadResult.Ready).state.arm)
    }

    @Test
    fun unavailable_adapter_and_clock_overflow_cannot_arm() = runTest {
        val store = InMemoryPanicStateStore()
        assertTrue(PanicAdmissionService(store).armRemote(request) is RemoteArmResult.Rejected)
        val service = PanicAdmissionService(store, PanicAdmissionEnvironment {
            PanicAdmissionObservation(start.copy(utcMs = Long.MAX_VALUE), true)
        })
        assertEquals(ArmRejectionReason.INVALID_CLOCK, (service.armRemote(request) as RemoteArmResult.Rejected).reason)
        val broken = PanicAdmissionService(store, PanicAdmissionEnvironment { throw SecurityException() })
        assertTrue(broken.armRemote(request) is RemoteArmResult.Rejected)
    }

    @Test
    fun failed_entropy_invalidates_previous_window_and_is_not_reported_as_armed() = runTest {
        val store = InMemoryPanicStateStore()
        val environment = PanicAdmissionEnvironment { PanicAdmissionObservation(start, true) }
        assertTrue(PanicAdmissionService(store, environment).armRemote(request) is RemoteArmResult.Armed)
        val rng = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { throw IllegalStateException("simulated RNG failure") }
        }
        val service = PanicAdmissionService(store, environment, RemotePanicTokenGenerator(rng))
        assertEquals(ArmRejectionReason.ENTROPY_FAILURE, (service.armRemote(request) as RemoteArmResult.Rejected).reason)
        assertNull((store.read() as PanicStoreReadResult.Ready).state.arm)
    }

    @Test
    fun observation_of_invalid_window_is_persisted_even_if_clock_later_returns() = runTest {
        val store = InMemoryPanicStateStore()
        var observation = PanicAdmissionObservation(start, true)
        val service = PanicAdmissionService(store, PanicAdmissionEnvironment { observation })
        val armed = service.armRemote(request) as RemoteArmResult.Armed
        observation = observation.copy(clock = start.copy(utcMs = start.utcMs + 10_000))
        assertNull((service.refreshRemoteState() as PanicStoreReadResult.Ready).state.arm)
        observation = PanicAdmissionObservation(start, true)
        val result = service.acceptSms(ValidatedSmsEnvelope(request.canonicalContactsE164.single(), armed.commands.single().command, true, true))
        assertEquals(AdmissionRejectionReason.NOT_ARMED, (result as AdmissionResult.Rejected).reason)
    }

    @Test
    fun sensitive_string_representations_are_redacted() {
        val secret = "f".repeat(64)
        val body = RemotePanicCommand.build("a".repeat(64), secret)
        for (value in listOf(
            RemotePanicCommand.parseExact(body)!!,
            TrustedContactCommand("+33600000000", body),
            ValidatedSmsEnvelope("+33600000000", body, true, true),
            TrustedContactVerifier("+33600000000", secret)
        )) {
            assertFalse(value.toString().contains(secret))
            assertFalse(value.toString().contains("+33600000000"))
        }
    }
}
