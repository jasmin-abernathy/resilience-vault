"""Adversarial specification tests; all numbers/tokens are synthetic fixtures."""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
import unittest
from model import Clock, HOUR, Model, Store, assembled_command

GEN = "a" * 64
NOW = Clock("boot-a", 100_000, 1_000_000)
CONTACTS = [(f"+3360000000{i}", f"{i + 1:064x}") for i in range(6)]


def command(i=0, generation=GEN):
    return f"RV1 {generation} {CONTACTS[i][1]}"


class SpecificationTests(unittest.TestCase):
    def setUp(self):
        self.s = Store()
        self.m = Model(self.s)

    def arm(self, n=1, duration=HOUR):
        return self.m.arm_local(GEN, CONTACTS[:n], duration, NOW)

    def send(self, i=0, now=NOW, body=None):
        return self.m.receive(CONTACTS[i][0], command(i) if body is None else body, now)

    def test_unarmed(self):
        self.assertFalse(self.send())

    def test_contact_bounds(self):
        for n in (0, 1, 5, 6):
            with self.subTest(n=n):
                self.assertEqual(n in (1, 5), self.arm(n))

    def test_duplicate_numbers_or_secrets(self):
        for pairs in ([CONTACTS[0], CONTACTS[0]],
                      [CONTACTS[0], (CONTACTS[1][0], CONTACTS[0][1])]):
            self.assertFalse(self.m.arm_local(GEN, pairs, HOUR, NOW))

    def test_duration_allowlist_and_cap(self):
        for h in (0, 1, 2, 6, 12, 24, 48, 72, 73):
            self.assertEqual(h in (1, 6, 12, 24, 48, 72), self.arm(duration=h * HOUR))

    def test_noncanonical_numbers(self):
        for number in ("0600000000", "0033600000000", "+33 600000000", "BANK", "+３３６００００００００"):
            self.assertFalse(self.m.arm_local(GEN, [(number, CONTACTS[0][1])], HOUR, NOW))

    def test_valid_contact_consumes_every_secret(self):
        self.arm(5)
        self.assertTrue(self.send(3))
        self.assertIsNone(self.s.arm)
        for i in range(5):
            self.assertFalse(self.send(i))
        self.assertEqual(1, self.s.accepted)

    def test_wrong_sender_secret_and_cross_contact(self):
        self.arm(5)
        self.assertFalse(self.send(5))
        self.assertFalse(self.send(0, body=command(1)))
        self.assertFalse(self.send(0, body=f"RV1 {GEN} {'f' * 64}"))
        self.assertTrue(self.send(0))

    def test_strict_grammar(self):
        self.arm()
        for body in (command() + "\n", " " + command(), command().upper(),
                     command().replace(" ", "\u00a0"), command()[:-1], "PANIC", command() * 2):
            self.assertFalse(self.send(body=body))
        self.assertTrue(self.send())

    def test_remove_contact_and_last_contact(self):
        self.arm(5)
        self.m.remove_local(CONTACTS[0][0])
        self.assertFalse(self.send(0))
        self.assertTrue(self.send(1))
        self.setUp()
        self.arm()
        self.m.remove_local(CONTACTS[0][0])
        self.assertIsNone(self.s.arm)

    def test_rotation(self):
        self.arm()
        new = "b" * 64
        self.assertTrue(self.m.arm_local(new, [(CONTACTS[0][0], "c" * 64)], HOUR, NOW))
        self.assertFalse(self.send())
        self.assertTrue(self.send(body=f"RV1 {new} {'c' * 64}"))

    def test_expiry_at_boundary_and_after(self):
        for d, expected in ((HOUR - 1, True), (HOUR, False), (HOUR + 1, False)):
            self.setUp()
            self.arm()
            self.assertEqual(expected, self.send(now=Clock(NOW.boot, NOW.elapsed+d, NOW.wall+d)))

    def test_clock_anomalies_permanently_disarm(self):
        for now in (Clock("boot-b", NOW.elapsed+10, NOW.wall+10),
                    Clock(None, NOW.elapsed, NOW.wall),
                    Clock(NOW.boot, NOW.elapsed-1, NOW.wall),
                    Clock(NOW.boot, NOW.elapsed, NOW.wall-3_000),
                    Clock(NOW.boot, NOW.elapsed, NOW.wall+3_000)):
            self.setUp()
            self.arm()
            self.assertFalse(self.send(now=now))
            self.assertFalse(self.send())

    def test_drift_tolerance_never_extends_monotonic_cap(self):
        self.arm()
        self.assertFalse(self.send(now=Clock(NOW.boot, NOW.elapsed+HOUR, NOW.wall+HOUR-1000)))

    def test_revoked_permission(self):
        self.arm()
        self.assertFalse(self.m.receive(CONTACTS[0][0], command(), NOW, permission=False))
        self.assertFalse(self.send())

    def test_same_boot_process_restart_keeps_window(self):
        self.arm()
        self.m = Model(self.s)
        self.assertTrue(self.send())

    def test_parallel_sms_have_exactly_one_winner(self):
        for indices in ((0, 0), (0, 1)):
            self.setUp()
            self.arm(5)
            barrier = Barrier(2)
            def invoke(i):
                barrier.wait()
                return self.send(i)
            with ThreadPoolExecutor(2) as pool:
                results = list(pool.map(invoke, indices))
            self.assertEqual(1, sum(results))
            self.assertEqual(1, self.s.accepted)

    def test_local_panic_invalidates_remote(self):
        self.arm(5)
        self.assertTrue(self.m.panic_local())
        self.assertFalse(self.send())
        self.assertFalse(self.arm())
        self.m.disarm_local()
        self.assertEqual("LOCAL_PENDING", self.s.phase)

    def test_multipart_contract(self):
        body = command()
        self.assertEqual(body, assembled_command([body[:60], body[60:]], True, True))
        for parts, complete, consistent in (([body[:60]], False, True),
                ([body], True, False), ([body] * 5, True, True), ([], True, True),
                ([body, "suffix"], True, True)):
            self.assertIsNone(assembled_command(parts, complete, consistent))

    def test_crash_boundaries_resume_before_open(self):
        for crash in ("before_keys", "after_keys", "after_local_checkpoint", "after_remote_effect"):
            self.setUp()
            self.arm()
            self.assertTrue(self.send())
            self.m.resume(crash=crash)
            self.m = Model(self.s)
            self.assertFalse(self.m.can_open())
            self.assertFalse(self.send())
            self.m.resume()
            self.assertEqual("COMPLETE", self.s.phase)
            self.assertFalse(self.s.keys_present)
            self.assertLess(self.s.events.index("destroy_keys"), self.s.events.index("remote_delete"))

    def test_key_failure_never_calls_network(self):
        self.arm()
        self.send()
        self.m.resume(fail="keys")
        self.assertEqual("LOCAL_PENDING", self.s.phase)
        self.assertEqual([], self.s.events)
        self.assertFalse(self.m.can_open())

    def test_offline_server_error_and_permanent_error(self):
        for result in ("offline", "500", "401"):
            self.setUp()
            self.arm()
            self.send()
            self.m.resume(remote=result)
            self.assertFalse(self.s.keys_present)
            self.assertEqual("POST_PENDING", self.s.phase)
            self.m.resume()
            self.assertEqual("COMPLETE", self.s.phase)

    def test_cleanup_failures_do_not_block_cloud(self):
        for failure in ("purged", "revoked"):
            self.setUp()
            self.arm()
            self.send()
            self.m.resume(fail=failure)
            self.assertTrue(self.s.remote_deleted)
            self.assertEqual("POST_PENDING", self.s.phase)
            self.m.resume()
            self.assertEqual("COMPLETE", self.s.phase)

    def test_unavailable_storage_never_accepts_or_opens(self):
        self.arm()
        self.s.unavailable = True
        self.assertFalse(self.send())
        self.assertFalse(self.m.can_open())
        self.assertEqual(0, self.s.accepted)

    def test_full_storage_before_and_after_acceptance(self):
        self.arm()
        self.s.writable = False
        self.assertFalse(self.send())
        self.assertFalse(self.m.can_open())
        self.s.writable = True
        self.assertTrue(self.send())
        self.s.writable = False
        self.m.resume()
        self.assertEqual("LOCAL_PENDING", self.s.phase)
        self.s.writable = True
        self.m.resume()
        self.assertEqual("COMPLETE", self.s.phase)

    def test_no_plaintext_token_in_persisted_arm(self):
        self.arm(5)
        for _, token in CONTACTS[:5]:
            self.assertNotIn(token, repr(self.s.arm.verifiers))


if __name__ == "__main__":
    unittest.main()
