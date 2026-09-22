import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from contracts import (header, validate_header, accept_checkpoint, Disk, State,
                       PanicModel, Crash, StorageFailure, DeleteServiceModel)


class FormatTests(unittest.TestCase):
    def setUp(self):
        self.encoded = header(1, bytes(range(16)), bytes(range(16, 32)), 1, 2, 3)

    def test_fixed_wire_vector(self):
        self.assertEqual(self.encoded.hex(),
            '52564c5401010001' '000102030405060708090a0b0c0d0e0f'
            '101112131415161718191a1b1c1d1e1f'
            '0000000000000001' '0000000000000002' '0000000000000003')
        self.assertEqual(validate_header(self.encoded, self.encoded), 3)

    def test_every_header_byte_is_bound_to_expected_context(self):
        for offset in range(64):
            changed = bytearray(self.encoded)
            changed[offset] ^= 1
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                validate_header(bytes(changed), self.encoded)

    def test_unknown_format_rejected_even_when_expected_is_untrusted(self):
        for offset, value in ((0, 0), (4, 2), (5, 99), (7, 2)):
            changed = bytearray(self.encoded)
            changed[offset] = value
            with self.assertRaises(ValueError):
                validate_header(bytes(changed), bytes(changed))

    def test_size_and_integer_boundaries(self):
        for kind, maximum in ((1, 1 << 30), (2, 8 << 20), (3, 64 << 10), (4, 64 << 10)):
            for length in (0, maximum):
                value = header(kind, bytes(16), bytes(16), 1, 1, length)
                self.assertEqual(validate_header(value, value), length)
            for length in (-1, maximum + 1):
                with self.assertRaises(ValueError):
                    header(kind, bytes(16), bytes(16), 1, 1, length)
        for epoch, revision in ((0, 1), (1, 0), (1 << 63, 1), (1, 1 << 63)):
            with self.assertRaises(ValueError):
                header(1, bytes(16), bytes(16), epoch, revision, 0)

    def test_short_and_extended_header(self):
        for value in (self.encoded[:-1], self.encoded + b'\x00'):
            with self.assertRaises(ValueError):
                validate_header(value, self.encoded)

    def test_rollback_fork_and_unproven_advance(self):
        self.assertTrue(accept_checkpoint(5, 'a', 5, 'a', False))
        self.assertTrue(accept_checkpoint(5, 'a', 6, 'b', True))
        self.assertFalse(accept_checkpoint(5, 'a', 4, 'a', True))
        self.assertFalse(accept_checkpoint(5, 'a', 5, 'b', True))
        self.assertFalse(accept_checkpoint(5, 'a', 6, 'b', False))


class PanicTests(unittest.TestCase):
    def test_crash_after_every_effect_then_reboot(self):
        baseline = PanicModel(Disk())
        baseline.run()
        for cut in range(1, len(baseline.events) + 1):
            with self.subTest(cut=cut):
                disk = Disk()
                first = PanicModel(disk, cut=cut)
                with self.assertRaises(Crash):
                    first.run()
                resumed = PanicModel(disk)
                resumed.run()
                self.assertEqual(disk.state, State.COMPLETE)
                self.assertFalse(disk.keys or disk.staging or disk.capsule)
                calls = first.events + resumed.events
                self.assertLess(calls.index('destroy_keys'), calls.index('network_delete'))

    def test_duplicate_trigger_is_idempotent(self):
        disk = Disk()
        PanicModel(disk).run()
        second = PanicModel(disk)
        second.run()
        self.assertNotIn('network_delete', second.events)
        self.assertEqual(disk.state, State.COMPLETE)

    def test_offline_then_accepted_then_deleted(self):
        disk = Disk()
        for response in ('offline', 'timeout', 'accepted', 'rate_limited', 'server_error'):
            PanicModel(disk).run(response)
            self.assertEqual(disk.state, State.DELETE_PENDING)
            self.assertFalse(disk.keys or disk.staging)
            self.assertTrue(disk.capsule)
        PanicModel(disk).run()
        self.assertEqual(disk.state, State.COMPLETE)

    def test_terminal_refusal_never_claims_deleted(self):
        for response in ('unauthorized', 'revoked', 'not_found', 'redirect', 'invalid'):
            disk = Disk()
            PanicModel(disk).run(response)
            self.assertEqual(disk.state, State.REMOTE_BLOCKED)
            second = PanicModel(disk)
            second.run()
            self.assertNotIn('network_delete', second.events)

    def test_local_mode_never_calls_network(self):
        disk = Disk(distant=False, capsule=False)
        model = PanicModel(disk)
        model.run()
        self.assertNotIn('network_delete', model.events)
        self.assertEqual(disk.state, State.COMPLETE)

    def test_full_storage_at_each_checkpoint(self):
        for state in (State.INTENT, State.KEYS_GONE, State.LOCAL_CLEAN,
                      State.DELETE_PENDING, State.COMPLETE):
            disk = Disk()
            model = PanicModel(disk, fail_write=state)
            with self.subTest(state=state), self.assertRaises(StorageFailure):
                model.run()
            self.assertFalse(disk.keys)
            self.assertTrue(disk.capsule)
            self.assertNotEqual(disk.state, State.COMPLETE)
            if state != State.COMPLETE:
                self.assertNotIn('network_delete', model.events)
            PanicModel(disk).run()
            self.assertIn(disk.state, (State.COMPLETE, State.QUARANTINED))

    def test_destruction_failure_stops_before_network(self):
        disk = Disk()
        model = PanicModel(disk, destroy_fails=True)
        with self.assertRaises(StorageFailure):
            model.run()
        self.assertTrue(disk.keys)
        self.assertTrue(model.closed)
        self.assertEqual(disk.state, State.INTENT)
        self.assertNotIn('network_delete', model.events)

    def test_corrupt_state_and_missing_capsule(self):
        disk = Disk(state='corrupt')
        model = PanicModel(disk)
        model.run()
        self.assertEqual(disk.state, State.QUARANTINED)
        self.assertFalse(disk.keys)
        self.assertNotIn('network_delete', model.events)
        disk = Disk(capsule=False)
        model = PanicModel(disk)
        model.run()
        self.assertEqual(disk.state, State.REMOTE_BLOCKED)
        self.assertNotIn('network_delete', model.events)


class CapabilityTests(unittest.TestCase):
    def test_only_exact_delete_is_allowed(self):
        service = DeleteServiceModel()
        for method in ('GET', 'HEAD', 'LIST', 'POST', 'PUT', 'PATCH', 'RESTORE', 'EXCHANGE'):
            self.assertEqual(service.request('delete-a', method, 'vault-a', 1, 'operation-a'), 'denied')
        for token, vault, generation, operation in (
            ('wrong', 'vault-a', 1, 'operation-a'), ('delete-a', 'vault-b', 1, 'operation-a'),
            ('delete-a', 'vault-a', 2, 'operation-a'), ('delete-a', 'vault-a', 1, 'wrong')):
            self.assertEqual(service.request(token, 'DELETE', vault, generation, operation), 'denied')
        self.assertEqual(service.tombstones, set())

    def test_tombstone_blocks_delayed_uploads_and_other_devices(self):
        service = DeleteServiceModel()
        self.assertEqual(service.request('delete-a', 'DELETE', 'vault-a', 1, 'operation-a', False), 'accepted')
        self.assertFalse(service.may_read_or_write('vault-a', 1))
        self.assertTrue(service.may_read_or_write('vault-a', 2))
        self.assertTrue(service.may_read_or_write('vault-b', 1))
        for _ in range(2):
            self.assertEqual(service.request('delete-a', 'DELETE', 'vault-a', 1, 'operation-a'), 'deleted')
        service.revoke('delete-a')
        self.assertEqual(service.request('delete-a', 'DELETE', 'vault-a', 1, 'operation-a'), 'denied')
        self.assertFalse(service.may_read_or_write('vault-a', 1))


class ActivationGateTests(unittest.TestCase):
    def test_bootstrap_stays_disabled(self):
        root = Path(__file__).resolve().parents[2]
        gradle = (root / 'app/build.gradle.kts').read_text()
        for flag in ('PRODUCTION_CRYPTO_READY', 'TELEGRAM_NATIVE_READY'):
            self.assertIn(f'buildConfigField("boolean", "{flag}", "false")', gradle)


if __name__ == '__main__':
    unittest.main()
