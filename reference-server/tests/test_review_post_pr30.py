from __future__ import annotations

import hashlib
import json
import shutil
import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path

from reference_server.proof import (
    DeleteCompletionProofCandidate,
    DeleteProofExpectation,
    ProofValidationError,
    require_complete_http_candidate,
    validate_complete_candidate,
)
from reference_server.store import (
    CapabilityUnavailable,
    ProvisioningRow,
    ReferenceStore,
    StoreNotFound,
    Tombstoned,
)

class SimulatedCrash(RuntimeError):
    pass


class CrashAt:
    def __init__(self, point: str) -> None:
        self.point = point
        self.fired = False

    def __call__(self, point: str) -> None:
        if point == self.point and not self.fired:
            self.fired = True
            raise SimulatedCrash(point)


class FaultInjectionMatrixTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.db = root / "main.sqlite"
        self.ledger = root / "ledger.sqlite"
        self.tenant = "tenant-1"
        self.service = "test-primary"
        self.vault = "1" * 64
        self.generation = "2" * 64
        self.operation = "3" * 64
        self.row = ProvisioningRow(
            tenant_id=self.tenant,
            service_id=self.service,
            operation_id=self.operation,
            vault_id=self.vault,
            generation=self.generation,
            request_digest="4" * 64,
            verifier_version="opaque-v1",
            verifier_hex="5" * 64,
            state="PROVISIONED",
        )
        self.next_delete = 0

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def _delete_id(self) -> str:
        self.next_delete += 1
        return f"{self.next_delete:032x}"

    def store(self, fault=None) -> ReferenceStore:
        return ReferenceStore(
            self.db,
            self.ledger,
            now=lambda: 1_700_000_000,
            delete_operation_id_factory=self._delete_id,
            fault_injector=fault,
        )

    def prepare(self, store: ReferenceStore) -> None:
        store.create_generation(
            tenant_id=self.tenant, service_id=self.service,
            vault_id=self.vault, generation=self.generation,
        )

    def provision(self, store: ReferenceStore) -> None:
        store.put_provisioning(self.row)

    def delete(self, store: ReferenceStore):
        return store.delete_generation(
            tenant_id=self.tenant, service_id=self.service,
            vault_id=self.vault, generation=self.generation,
        )

    def test_provisioning_crash_before_commit_is_absent_and_retriable(self) -> None:
        store = self.store(CrashAt("provisioning.before_commit"))
        self.prepare(store)
        with self.assertRaises(SimulatedCrash):
            self.provision(store)
        restarted = self.store()
        self.assertIsNone(restarted.get_provisioning(tenant_id=self.tenant, operation_id=self.operation))
        stored, created = restarted.put_provisioning(self.row)
        self.assertTrue(created)
        self.assertEqual(self.row, stored)

    def test_provisioning_crash_after_commit_is_durable_and_idempotent(self) -> None:
        store = self.store(CrashAt("provisioning.after_commit"))
        self.prepare(store)
        with self.assertRaises(SimulatedCrash):
            self.provision(store)
        restarted = self.store()
        self.assertEqual(self.row, restarted.get_provisioning(tenant_id=self.tenant, operation_id=self.operation))
        stored, created = restarted.put_provisioning(self.row)
        self.assertFalse(created)
        self.assertEqual(self.row, stored)

    def test_crash_after_tombstone_before_outbox_rolls_back_entire_delete(self) -> None:
        store = self.store(CrashAt("delete.after_tombstone"))
        self.prepare(store)
        self.provision(store)
        with self.assertRaises(SimulatedCrash):
            self.delete(store)
        restarted = self.store()
        self.assertFalse(restarted.generation_tombstoned(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))
        status = self.delete(restarted)
        self.assertEqual("PENDING", status.state)

    def test_crash_after_primary_mark_before_outbox_rolls_back_entire_delete(self) -> None:
        store = self.store(CrashAt("delete.after_primary_mark"))
        self.prepare(store)
        self.provision(store)
        with self.assertRaises(SimulatedCrash):
            self.delete(store)
        restarted = self.store()
        self.assertFalse(restarted.generation_tombstoned(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))

    def test_delete_response_lost_after_commit_replays_same_operation(self) -> None:
        store = self.store(CrashAt("delete.after_commit"))
        self.prepare(store)
        self.provision(store)
        with self.assertRaises(SimulatedCrash):
            self.delete(store)
        restarted = self.store()
        first = self.delete(restarted)
        second = self.delete(restarted)
        self.assertEqual("PENDING", first.state)
        self.assertEqual(first.delete_operation_id, second.delete_operation_id)

    def test_purge_crash_after_object_and_staging_delete_rolls_back(self) -> None:
        store = self.store()
        self.prepare(store)
        self.provision(store)
        store.stage_upload(
            upload_id="staged", object_id="staged-object", tenant_id=self.tenant,
            service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        store.stage_upload(
            upload_id="active", object_id="active-object", tenant_id=self.tenant,
            service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        store.finalize_upload(upload_id="active")
        self.delete(store)

        crashed = self.store(CrashAt("purge.after_delete_before_complete"))
        with self.assertRaises(SimulatedCrash):
            crashed.run_purge_once()
        restarted = self.store()
        self.assertEqual(1, restarted.active_object_count(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))
        self.assertEqual(1, restarted.staged_upload_count(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))
        self.assertEqual("PENDING", self.delete(restarted).state)

    def test_purge_crash_after_complete_mark_before_commit_rolls_back_complete(self) -> None:
        store = self.store()
        self.prepare(store)
        self.provision(store)
        self.delete(store)
        crashed = self.store(CrashAt("purge.after_state_before_commit"))
        with self.assertRaises(SimulatedCrash):
            crashed.run_purge_once()
        restarted = self.store()
        self.assertEqual("PENDING", self.delete(restarted).state)
        [complete] = restarted.run_purge_once()
        self.assertEqual("COMPLETE", complete.state)

    def test_complete_response_lost_after_commit_recovers_as_complete(self) -> None:
        store = self.store()
        self.prepare(store)
        self.provision(store)
        self.delete(store)
        crashed = self.store(CrashAt("purge.after_commit"))
        with self.assertRaises(SimulatedCrash):
            crashed.run_purge_once()
        restarted = self.store()
        self.assertEqual("COMPLETE", self.delete(restarted).state)

    def test_staged_upload_is_in_purge_scope_and_blocks_complete_until_removed(self) -> None:
        store = self.store()
        self.prepare(store)
        self.provision(store)
        store.stage_upload(
            upload_id="staged", object_id="staged-object", tenant_id=self.tenant,
            service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        self.delete(store)
        self.assertEqual("PENDING", self.delete(store).state)
        [complete] = store.run_purge_once(max_objects_per_generation=0)
        self.assertEqual("COMPLETE", complete.state)
        self.assertEqual(0, store.staged_upload_count(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))

    def test_restore_old_primary_with_staging_reopens_pending_and_repurgess(self) -> None:
        store = self.store()
        self.prepare(store)
        self.provision(store)
        store.stage_upload(
            upload_id="staged", object_id="staged-object", tenant_id=self.tenant,
            service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        backup = Path(self.tmp.name) / "pre-delete.sqlite"
        shutil.copy2(self.db, backup)
        self.delete(store)
        store.run_purge_once()
        self.assertEqual("COMPLETE", self.delete(store).state)

        shutil.copy2(backup, self.db)
        restored = self.store()
        self.assertEqual("PENDING", self.delete(restored).state)
        self.assertEqual(1, restored.staged_upload_count(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))
        [complete] = restored.run_purge_once()
        self.assertEqual("COMPLETE", complete.state)

    def test_corrupt_ledger_blocks_store_startup(self) -> None:
        store = self.store()
        self.prepare(store)
        self.ledger.write_bytes(b"not-a-sqlite-database")
        with self.assertRaises(sqlite3.DatabaseError):
            self.store()

    def test_double_resume_is_idempotent(self) -> None:
        store = self.store()
        self.prepare(store)
        self.provision(store)
        first = self.delete(store)
        a = self.store()
        b = self.store()
        self.assertEqual(first.delete_operation_id, self.delete(a).delete_operation_id)
        self.assertEqual(first.delete_operation_id, self.delete(b).delete_operation_id)

    def test_delete_concurrent_with_revoke_never_becomes_active_after_tombstone(self) -> None:
        store = self.store()
        self.prepare(store)
        self.provision(store)
        barrier = threading.Barrier(3)
        outcomes: list[str] = []

        def do_delete() -> None:
            barrier.wait()
            try:
                outcomes.append("delete:" + self.delete(store).state)
            except CapabilityUnavailable:
                outcomes.append("delete:blocked")

        def do_revoke() -> None:
            barrier.wait()
            try:
                outcomes.append("revoke:" + store.revoke_provisioning(
                    tenant_id=self.tenant, operation_id=self.operation,
                ).state)
            except StoreNotFound:
                outcomes.append("revoke:not-found")

        t1 = threading.Thread(target=do_delete)
        t2 = threading.Thread(target=do_revoke)
        t1.start(); t2.start(); barrier.wait(); t1.join(); t2.join()
        self.assertEqual(2, len(outcomes))
        tombstoned = store.generation_tombstoned(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        if tombstoned:
            with self.assertRaises(Tombstoned):
                store.stage_upload(
                    upload_id="late", object_id="late", tenant_id=self.tenant,
                    service_id=self.service, vault_id=self.vault, generation=self.generation,
                )
        else:
            self.assertIn("delete:blocked", outcomes)

class ProofCandidateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.body = {
            "schemaVersion": 1,
            "serviceId": "test-primary",
            "tenantId": "tenant-1",
            "vaultId": "1" * 64,
            "generation": "2" * 64,
            "deleteOperationId": "3" * 32,
            "requestDigest": "4" * 64,
            "state": "COMPLETE",
            "tombstoneRevision": 9,
            "responseId": "5" * 32,
            "verifiedAtEpochSeconds": 1_700_000_100,
            "purgeScope": ["active", "replicas", "staging", "versions"],
            "backupPolicyId": "policy-v1",
        }
        self.expected = DeleteProofExpectation(
            service_id="test-primary",
            tenant_id="tenant-1",
            vault_id="1" * 64,
            generation="2" * 64,
            delete_operation_id="3" * 32,
            request_digest="4" * 64,
            min_tombstone_revision=9,
            required_purge_scope=frozenset({"active", "staging"}),
            min_verified_at_epoch_seconds=1_700_000_000,
        )

    def raw(self, body=None) -> bytes:
        return json.dumps(body or self.body, sort_keys=True, separators=(",", ":")).encode()

    def test_complete_candidate_exact_binding_is_accepted_as_schema_only(self) -> None:
        proof = require_complete_http_candidate(200, self.raw(), self.expected)
        self.assertEqual("COMPLETE", proof.state)
        self.assertEqual(9, proof.tombstone_revision)

    def test_bare_200_and_204_never_prove_complete(self) -> None:
        with self.assertRaises(ProofValidationError):
            require_complete_http_candidate(200, b"", self.expected)
        with self.assertRaises(ProofValidationError):
            require_complete_http_candidate(204, b"", self.expected)
        with self.assertRaises(ProofValidationError):
            require_complete_http_candidate(204, self.raw(), self.expected)

    def test_cross_generation_binding_is_rejected(self) -> None:
        other = dict(self.body)
        other["generation"] = "6" * 64
        proof = DeleteCompletionProofCandidate.parse_json(self.raw(other))
        with self.assertRaisesRegex(ProofValidationError, "binding mismatch"):
            validate_complete_candidate(proof, self.expected)

    def test_cross_tenant_and_cross_operation_binding_are_rejected(self) -> None:
        for key, value in (("tenantId", "tenant-2"), ("deleteOperationId", "6" * 32)):
            other = dict(self.body)
            other[key] = value
            proof = DeleteCompletionProofCandidate.parse_json(self.raw(other))
            with self.subTest(key=key), self.assertRaisesRegex(ProofValidationError, "binding mismatch"):
                validate_complete_candidate(proof, self.expected)

    def test_stale_tombstone_revision_is_rejected(self) -> None:
        stale = dict(self.body)
        stale["tombstoneRevision"] = 8
        proof = DeleteCompletionProofCandidate.parse_json(self.raw(stale))
        with self.assertRaisesRegex(ProofValidationError, "stale tombstone revision"):
            validate_complete_candidate(proof, self.expected)

    def test_stale_verification_time_is_rejected(self) -> None:
        stale = dict(self.body)
        stale["verifiedAtEpochSeconds"] = 1_699_999_999
        proof = DeleteCompletionProofCandidate.parse_json(self.raw(stale))
        with self.assertRaisesRegex(ProofValidationError, "stale verification time"):
            validate_complete_candidate(proof, self.expected)

    def test_incomplete_purge_scope_is_rejected(self) -> None:
        partial = dict(self.body)
        partial["purgeScope"] = ["active"]
        proof = DeleteCompletionProofCandidate.parse_json(self.raw(partial))
        with self.assertRaisesRegex(ProofValidationError, "purge scope incomplete"):
            validate_complete_candidate(proof, self.expected)

    def test_pending_state_is_not_complete_proof(self) -> None:
        pending = dict(self.body)
        pending["state"] = "PENDING"
        proof = DeleteCompletionProofCandidate.parse_json(self.raw(pending))
        with self.assertRaisesRegex(ProofValidationError, "not COMPLETE"):
            validate_complete_candidate(proof, self.expected)

    def test_missing_unknown_and_duplicate_fields_are_rejected(self) -> None:
        missing = dict(self.body)
        missing.pop("responseId")
        with self.assertRaises(ProofValidationError):
            DeleteCompletionProofCandidate.parse_json(self.raw(missing))
        unknown = dict(self.body)
        unknown["signature"] = "fake"
        with self.assertRaises(ProofValidationError):
            DeleteCompletionProofCandidate.parse_json(self.raw(unknown))
        raw = self.raw().decode().replace('"schemaVersion":1', '"schemaVersion":1,"schemaVersion":1')
        with self.assertRaises(ProofValidationError):
            DeleteCompletionProofCandidate.parse_json(raw.encode())

    def test_schema_has_no_signature_claim(self) -> None:
        proof = DeleteCompletionProofCandidate.parse_json(self.raw())
        self.assertFalse(hasattr(proof, "signature"))
        self.assertFalse(hasattr(proof, "public_key"))

DOMAIN = b"RV-DELETE-CAPABILITY-VERIFIER-CANDIDATE-V1\x00"

def candidate_verifier(secret: bytes) -> str:
    if len(secret) != 32:
        raise ValueError("candidate secret must be exactly 32 bytes")
    return hashlib.sha256(DOMAIN + secret).hexdigest()

class CandidateVerifierVectorTest(unittest.TestCase):
    def test_language_neutral_vectors(self) -> None:
        vectors = [
            (
                "00" * 32,
                "ac6f12e35b580bb9ea60fe826f1bde4d83d1b486690a8ad064e59529dec8ee86",
            ),
            (
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
                "6369cd897518c9a2c6b917ebea4c7f8453c67dd3fd86ffacd59a10198d150d1f",
            ),
            (
                "ff" * 32,
                "c78ae2eefd5448a53b1fa87f79ba3edcacf6149f78692ac9ba99279d8b2a37c6",
            ),
        ]
        for secret_hex, expected in vectors:
            with self.subTest(secret=secret_hex[:8]):
                self.assertEqual(expected, candidate_verifier(bytes.fromhex(secret_hex)))

    def test_wrong_secret_length_is_rejected(self) -> None:
        for size in (0, 16, 31, 33, 64):
            with self.subTest(size=size), self.assertRaises(ValueError):
                candidate_verifier(bytes(size))

    def test_domain_separation_changes_output(self) -> None:
        secret = bytes(range(32))
        candidate = candidate_verifier(secret)
        undomained = hashlib.sha256(secret).hexdigest()
        self.assertNotEqual(candidate, undomained)

if __name__ == "__main__":
    unittest.main()
