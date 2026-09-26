from __future__ import annotations

import shutil
import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path

from reference_server.digest import ProvisioningDigestInput, request_digest_hex
from reference_server.store import (
    CapabilityUnavailable,
    ProvisioningRow,
    ReferenceStore,
    StoreConflict,
    StoreNotFound,
    Tombstoned,
)


class StoreIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.db = root / "main.sqlite"
        self.ledger = root / "tombstone.sqlite"
        self.next_delete = 0
        self.store = ReferenceStore(
            self.db,
            self.ledger,
            now=lambda: 1_700_000_000,
            delete_operation_id_factory=self._delete_id,
        )
        self.tenant = "tenant-1"
        self.service = "test-primary"
        self.vault = "1" * 64
        self.generation = "2" * 64
        self.operation = "3" * 64
        self.verifier = "4" * 64
        self.store.create_generation(
            tenant_id=self.tenant,
            service_id=self.service,
            vault_id=self.vault,
            generation=self.generation,
        )

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def _delete_id(self) -> str:
        self.next_delete += 1
        return f"{self.next_delete:032x}"

    def _row(self, *, verifier: str | None = None, operation: str | None = None) -> ProvisioningRow:
        verifier = verifier or self.verifier
        operation = operation or self.operation
        digest = request_digest_hex(
            ProvisioningDigestInput(
                service_id=self.service,
                tenant_id=self.tenant,
                vault_id=self.vault,
                generation=self.generation,
                operation_id=operation,
                verifier_version="opaque-v1",
                verifier_hex=verifier,
            )
        )
        return ProvisioningRow(
            tenant_id=self.tenant,
            service_id=self.service,
            operation_id=operation,
            vault_id=self.vault,
            generation=self.generation,
            request_digest=digest,
            verifier_version="opaque-v1",
            verifier_hex=verifier,
            state="PROVISIONED",
        )

    def test_identical_concurrent_puts_commit_once_and_replay_same_row(self) -> None:
        row = self._row()
        barrier = threading.Barrier(3)
        results: list[tuple[ProvisioningRow, bool]] = []
        errors: list[BaseException] = []

        def worker() -> None:
            barrier.wait()
            try:
                results.append(self.store.put_provisioning(row))
            except BaseException as exc:  # test collection
                errors.append(exc)

        threads = [threading.Thread(target=worker) for _ in range(2)]
        for t in threads: t.start()
        barrier.wait()
        for t in threads: t.join()
        self.assertEqual([], errors)
        self.assertEqual(2, len(results))
        self.assertEqual({True, False}, {created for _, created in results})
        self.assertEqual({row}, {stored for stored, _ in results})

    def test_contradictory_put_same_operation_is_conflict(self) -> None:
        first = self._row()
        self.store.put_provisioning(first)
        with self.assertRaises(StoreConflict):
            self.store.put_provisioning(self._row(verifier="5" * 64))
        self.assertEqual(first, self.store.get_provisioning(tenant_id=self.tenant, operation_id=self.operation))

    def test_crash_after_commit_before_response_is_recovered_by_exact_replay(self) -> None:
        row = self._row()
        stored, created = self.store.put_provisioning(row)
        self.assertTrue(created)
        # Simulate process/transport loss after durable commit: create a fresh store and replay.
        restarted = ReferenceStore(self.db, self.ledger, now=lambda: 1_700_000_001)
        replay, created_again = restarted.put_provisioning(row)
        self.assertFalse(created_again)
        self.assertEqual(stored, replay)

    def test_revocation_and_provisioning_race_never_resurrects_duplicate_state(self) -> None:
        row = self._row()
        barrier = threading.Barrier(3)
        outcomes: list[str] = []

        def provision() -> None:
            barrier.wait()
            try:
                self.store.put_provisioning(row)
                outcomes.append("provisioned")
            except StoreConflict:
                outcomes.append("conflict")

        def revoke() -> None:
            barrier.wait()
            try:
                self.store.revoke_provisioning(tenant_id=self.tenant, operation_id=self.operation)
                outcomes.append("revoked")
            except StoreNotFound:
                outcomes.append("not-found")

        a = threading.Thread(target=provision)
        b = threading.Thread(target=revoke)
        a.start(); b.start(); barrier.wait(); a.join(); b.join()
        final = self.store.get_provisioning(tenant_id=self.tenant, operation_id=self.operation)
        self.assertIsNotNone(final)
        self.assertIn(final.state, {"PROVISIONED", "REVOKED"})
        self.assertEqual(2, len(outcomes))

    def test_delete_and_finalize_upload_are_serialized_by_tombstone_authority(self) -> None:
        self.store.put_provisioning(self._row())
        self.store.stage_upload(
            upload_id="u1", object_id="o1", tenant_id=self.tenant, service_id=self.service,
            vault_id=self.vault, generation=self.generation,
        )
        barrier = threading.Barrier(3)
        outcomes: list[str] = []

        def finalize() -> None:
            barrier.wait()
            try:
                self.store.finalize_upload(upload_id="u1")
                outcomes.append("finalized")
            except Tombstoned:
                outcomes.append("blocked")

        def delete() -> None:
            barrier.wait()
            status = self.store.delete_generation(
                tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
            )
            outcomes.append("delete-" + status.state)

        a = threading.Thread(target=finalize)
        b = threading.Thread(target=delete)
        a.start(); b.start(); barrier.wait(); a.join(); b.join()
        self.assertTrue(self.store.generation_tombstoned(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))
        self.assertIn("delete-PENDING", outcomes)
        self.assertIn(outcomes[0], {"finalized", "delete-PENDING", "blocked"})
        # No later finalization is allowed after the tombstone is durable.
        with self.assertRaises(Tombstoned):
            self.store.stage_upload(
                upload_id="u2", object_id="o2", tenant_id=self.tenant, service_id=self.service,
                vault_id=self.vault, generation=self.generation,
            )

    def test_tombstone_and_outbox_roll_back_together_if_outbox_insert_fails(self) -> None:
        self.store.put_provisioning(self._row())
        conn = sqlite3.connect(self.db)
        try:
            conn.executescript(
                """
                CREATE TRIGGER fail_purge_outbox
                BEFORE INSERT ON purge_outbox
                BEGIN
                    SELECT RAISE(ABORT, 'simulated outbox failure');
                END;
                """
            )
            conn.commit()
        finally:
            conn.close()

        with self.assertRaises(sqlite3.IntegrityError):
            self.store.delete_generation(
                tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
            )
        self.assertFalse(self.store.generation_tombstoned(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))

    def test_crash_after_tombstone_before_worker_stays_pending_after_restart(self) -> None:
        self.store.put_provisioning(self._row())
        first = self.store.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        self.assertEqual("PENDING", first.state)
        restarted = ReferenceStore(self.db, self.ledger)
        replay = restarted.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        self.assertEqual(first.delete_operation_id, replay.delete_operation_id)
        self.assertEqual("PENDING", replay.state)

    def test_partial_purge_never_returns_complete(self) -> None:
        self.store.put_provisioning(self._row())
        for i in range(2):
            self.store.stage_upload(
                upload_id=f"u{i}", object_id=f"o{i}", tenant_id=self.tenant, service_id=self.service,
                vault_id=self.vault, generation=self.generation,
            )
            self.store.finalize_upload(upload_id=f"u{i}")
        first = self.store.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        self.assertEqual("PENDING", first.state)
        [partial] = self.store.run_purge_once(max_objects_per_generation=1)
        self.assertEqual("PENDING", partial.state)
        replay = self.store.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        self.assertEqual("PENDING", replay.state)
        [complete] = self.store.run_purge_once(max_objects_per_generation=10)
        self.assertEqual("COMPLETE", complete.state)
        final = self.store.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        self.assertEqual("COMPLETE", final.state)
        self.assertEqual(first.delete_operation_id, final.delete_operation_id)

    def test_restore_of_primary_backup_before_tombstone_is_reconciled_from_ledger(self) -> None:
        self.store.put_provisioning(self._row())
        self.store.stage_upload(
            upload_id="u1", object_id="o1", tenant_id=self.tenant, service_id=self.service,
            vault_id=self.vault, generation=self.generation,
        )
        self.store.finalize_upload(upload_id="u1")
        backup = Path(self.tmp.name) / "pre-delete.sqlite"
        shutil.copy2(self.db, backup)

        self.store.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        shutil.copy2(backup, self.db)

        restored = ReferenceStore(self.db, self.ledger)
        self.assertTrue(restored.generation_tombstoned(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))
        with self.assertRaises(Tombstoned):
            restored.stage_upload(
                upload_id="u2", object_id="o2", tenant_id=self.tenant, service_id=self.service,
                vault_id=self.vault, generation=self.generation,
            )
        [status] = restored.run_purge_once(max_objects_per_generation=10)
        self.assertEqual("COMPLETE", status.state)
        self.assertEqual(0, restored.active_object_count(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        ))

    def test_all_active_storage_paths_consult_tombstone_authority(self) -> None:
        self.store.put_provisioning(self._row())
        self.store.stage_upload(
            upload_id="u1", object_id="o1", tenant_id=self.tenant, service_id=self.service,
            vault_id=self.vault, generation=self.generation,
        )
        self.store.finalize_upload(upload_id="u1")
        self.assertEqual("o1", self.store.read_object(object_id="o1"))
        self.store.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )

        with self.assertRaises(Tombstoned):
            self.store.read_object(object_id="o1")
        with self.assertRaises(Tombstoned):
            self.store.stage_upload(
                upload_id="u2", object_id="o2", tenant_id=self.tenant, service_id=self.service,
                vault_id=self.vault, generation=self.generation,
            )
        with self.assertRaises(Tombstoned):
            self.store.administrative_restore_object(
                object_id="restored", tenant_id=self.tenant, service_id=self.service,
                vault_id=self.vault, generation=self.generation,
            )

    def test_restore_of_predelete_backup_after_prior_complete_reopens_purge_as_pending(self) -> None:
        self.store.put_provisioning(self._row())
        self.store.stage_upload(
            upload_id="u1", object_id="o1", tenant_id=self.tenant, service_id=self.service,
            vault_id=self.vault, generation=self.generation,
        )
        self.store.finalize_upload(upload_id="u1")
        backup = Path(self.tmp.name) / "pre-delete-complete.sqlite"
        shutil.copy2(self.db, backup)

        first = self.store.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        [complete] = self.store.run_purge_once(max_objects_per_generation=10)
        self.assertEqual("COMPLETE", complete.state)
        self.assertEqual(first.delete_operation_id, complete.delete_operation_id)

        shutil.copy2(backup, self.db)
        restored = ReferenceStore(self.db, self.ledger)
        replay = restored.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        self.assertEqual("PENDING", replay.state)
        self.assertEqual(first.delete_operation_id, replay.delete_operation_id)
        [recomplete] = restored.run_purge_once(max_objects_per_generation=10)
        self.assertEqual("COMPLETE", recomplete.state)
        final = restored.delete_generation(
            tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
        )
        self.assertEqual("COMPLETE", final.state)

    def test_concurrent_contradictory_puts_have_one_commit_and_one_conflict(self) -> None:
        rows = [self._row(verifier="4" * 64), self._row(verifier="5" * 64)]
        barrier = threading.Barrier(3)
        outcomes: list[str] = []

        def worker(row: ProvisioningRow) -> None:
            barrier.wait()
            try:
                self.store.put_provisioning(row)
                outcomes.append("committed:" + row.request_digest)
            except StoreConflict:
                outcomes.append("conflict:" + row.request_digest)

        threads = [threading.Thread(target=worker, args=(row,)) for row in rows]
        for t in threads: t.start()
        barrier.wait()
        for t in threads: t.join()
        self.assertEqual(1, sum(x.startswith("committed:") for x in outcomes))
        self.assertEqual(1, sum(x.startswith("conflict:") for x in outcomes))
        final = self.store.get_provisioning(tenant_id=self.tenant, operation_id=self.operation)
        self.assertIsNotNone(final)
        self.assertIn(final.request_digest, {row.request_digest for row in rows})

    def test_revoked_provisioning_cannot_authorize_delete_even_if_authenticator_would_accept(self) -> None:
        self.store.put_provisioning(self._row())
        self.store.revoke_provisioning(tenant_id=self.tenant, operation_id=self.operation)
        with self.assertRaises(CapabilityUnavailable):
            self.store.delete_generation(
                tenant_id=self.tenant, service_id=self.service, vault_id=self.vault, generation=self.generation,
            )


if __name__ == "__main__":
    unittest.main()
