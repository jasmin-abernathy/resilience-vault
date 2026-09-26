from __future__ import annotations

from dataclasses import dataclass
from contextlib import closing
import secrets
import sqlite3
import time
from pathlib import Path
from typing import Callable


class StoreConflict(RuntimeError):
    pass


class StoreNotFound(RuntimeError):
    pass


class Tombstoned(RuntimeError):
    pass


class CapabilityUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class ProvisioningRow:
    tenant_id: str
    service_id: str
    operation_id: str
    vault_id: str
    generation: str
    request_digest: str
    verifier_version: str
    verifier_hex: str
    state: str


@dataclass(frozen=True)
class DeleteStatus:
    tenant_id: str
    service_id: str
    vault_id: str
    generation: str
    delete_operation_id: str
    state: str


class ReferenceStore:
    """Local-only SQLite model. The tombstone ledger is a distinct durable authority.

    Production storage/backup topology is intentionally not inferred from this reference model.
    """

    def __init__(
        self,
        database_path: str | Path,
        tombstone_ledger_path: str | Path,
        *,
        now: Callable[[], int] | None = None,
        delete_operation_id_factory: Callable[[], str] | None = None,
    ) -> None:
        self.database_path = Path(database_path)
        self.tombstone_ledger_path = Path(tombstone_ledger_path)
        self.now = now or (lambda: int(time.time()))
        self.delete_operation_id_factory = delete_operation_id_factory or (lambda: secrets.token_hex(16))
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self.tombstone_ledger_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()
        self.reconcile_tombstones()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(
            self.database_path,
            timeout=5.0,
            isolation_level=None,
            check_same_thread=False,
        )
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=DELETE")
        conn.execute("PRAGMA synchronous=FULL")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("ATTACH DATABASE ? AS ledger", (str(self.tombstone_ledger_path),))
        conn.execute("PRAGMA ledger.journal_mode=DELETE")
        conn.execute("PRAGMA ledger.synchronous=FULL")
        return conn

    def _initialize(self) -> None:
        with closing(self._connect()) as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS vault_generations (
                    tenant_id TEXT NOT NULL,
                    service_id TEXT NOT NULL,
                    vault_id TEXT NOT NULL,
                    generation TEXT NOT NULL,
                    tombstoned INTEGER NOT NULL DEFAULT 0 CHECK (tombstoned IN (0,1)),
                    PRIMARY KEY (tenant_id, service_id, vault_id, generation)
                );
                CREATE TABLE IF NOT EXISTS provisionings (
                    tenant_id TEXT NOT NULL,
                    service_id TEXT NOT NULL,
                    operation_id TEXT NOT NULL,
                    vault_id TEXT NOT NULL,
                    generation TEXT NOT NULL,
                    request_digest TEXT NOT NULL,
                    verifier_version TEXT NOT NULL,
                    verifier_hex TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('PROVISIONED','REVOKED')),
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, operation_id),
                    UNIQUE (tenant_id, service_id, vault_id, generation)
                );
                CREATE TABLE IF NOT EXISTS staged_uploads (
                    upload_id TEXT PRIMARY KEY,
                    tenant_id TEXT NOT NULL,
                    service_id TEXT NOT NULL,
                    vault_id TEXT NOT NULL,
                    generation TEXT NOT NULL,
                    object_id TEXT NOT NULL UNIQUE
                );
                CREATE TABLE IF NOT EXISTS active_objects (
                    object_id TEXT PRIMARY KEY,
                    tenant_id TEXT NOT NULL,
                    service_id TEXT NOT NULL,
                    vault_id TEXT NOT NULL,
                    generation TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS purge_outbox (
                    tenant_id TEXT NOT NULL,
                    service_id TEXT NOT NULL,
                    vault_id TEXT NOT NULL,
                    generation TEXT NOT NULL,
                    delete_operation_id TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('PENDING','COMPLETE')),
                    PRIMARY KEY (tenant_id, service_id, vault_id, generation)
                );
                CREATE TABLE IF NOT EXISTS ledger.tombstones (
                    tenant_id TEXT NOT NULL,
                    service_id TEXT NOT NULL,
                    vault_id TEXT NOT NULL,
                    generation TEXT NOT NULL,
                    delete_operation_id TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('PENDING','COMPLETE')),
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, service_id, vault_id, generation)
                );
                """
            )

    def create_generation(self, *, tenant_id: str, service_id: str, vault_id: str, generation: str) -> None:
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                if self._ledger_tombstone(conn, tenant_id, service_id, vault_id, generation):
                    raise Tombstoned("generation has durable tombstone")
                conn.execute(
                    "INSERT OR IGNORE INTO vault_generations(tenant_id,service_id,vault_id,generation,tombstoned) VALUES(?,?,?,?,0)",
                    (tenant_id, service_id, vault_id, generation),
                )
                conn.commit()
            except Exception:
                conn.rollback()
                raise

    def put_provisioning(self, row: ProvisioningRow) -> tuple[ProvisioningRow, bool]:
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                self._require_live_generation(conn, row.tenant_id, row.service_id, row.vault_id, row.generation)
                existing = conn.execute(
                    "SELECT * FROM provisionings WHERE tenant_id=? AND operation_id=?",
                    (row.tenant_id, row.operation_id),
                ).fetchone()
                if existing is not None:
                    current = self._provisioning_from_row(existing)
                    if current == row:
                        conn.commit()
                        return current, False
                    raise StoreConflict("operationId already committed with different binding or digest")

                generation_existing = conn.execute(
                    "SELECT * FROM provisionings WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                    (row.tenant_id, row.service_id, row.vault_id, row.generation),
                ).fetchone()
                if generation_existing is not None:
                    raise StoreConflict("generation already has a different provisioning operation")

                now = self.now()
                conn.execute(
                    """INSERT INTO provisionings(
                        tenant_id,service_id,operation_id,vault_id,generation,request_digest,
                        verifier_version,verifier_hex,state,created_at,updated_at
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    (
                        row.tenant_id,
                        row.service_id,
                        row.operation_id,
                        row.vault_id,
                        row.generation,
                        row.request_digest,
                        row.verifier_version,
                        row.verifier_hex,
                        row.state,
                        now,
                        now,
                    ),
                )
                conn.commit()
                return row, True
            except Exception:
                conn.rollback()
                raise

    def get_provisioning(self, *, tenant_id: str, operation_id: str) -> ProvisioningRow | None:
        with closing(self._connect()) as conn:
            row = conn.execute(
                "SELECT * FROM provisionings WHERE tenant_id=? AND operation_id=?",
                (tenant_id, operation_id),
            ).fetchone()
            return self._provisioning_from_row(row) if row is not None else None

    def revoke_provisioning(self, *, tenant_id: str, operation_id: str) -> ProvisioningRow:
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute(
                    "SELECT * FROM provisionings WHERE tenant_id=? AND operation_id=?",
                    (tenant_id, operation_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound("provisioning operation not found")
                current = self._provisioning_from_row(row)
                if current.state != "REVOKED":
                    conn.execute(
                        "UPDATE provisionings SET state='REVOKED', updated_at=? WHERE tenant_id=? AND operation_id=?",
                        (self.now(), tenant_id, operation_id),
                    )
                    current = ProvisioningRow(**{**current.__dict__, "state": "REVOKED"})
                conn.commit()
                return current
            except Exception:
                conn.rollback()
                raise

    def stage_upload(
        self,
        *,
        upload_id: str,
        object_id: str,
        tenant_id: str,
        service_id: str,
        vault_id: str,
        generation: str,
    ) -> None:
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                self._require_live_generation(conn, tenant_id, service_id, vault_id, generation)
                conn.execute(
                    "INSERT INTO staged_uploads(upload_id,tenant_id,service_id,vault_id,generation,object_id) VALUES(?,?,?,?,?,?)",
                    (upload_id, tenant_id, service_id, vault_id, generation, object_id),
                )
                conn.commit()
            except Exception:
                conn.rollback()
                raise

    def finalize_upload(self, *, upload_id: str) -> None:
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                staged = conn.execute("SELECT * FROM staged_uploads WHERE upload_id=?", (upload_id,)).fetchone()
                if staged is None:
                    raise StoreNotFound("staged upload not found")
                key = (staged["tenant_id"], staged["service_id"], staged["vault_id"], staged["generation"])
                if self._ledger_tombstone(conn, *key):
                    raise Tombstoned("generation tombstoned")
                generation_row = conn.execute(
                    "SELECT tombstoned FROM vault_generations WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                    key,
                ).fetchone()
                if generation_row is None or generation_row["tombstoned"]:
                    raise Tombstoned("generation unavailable for finalization")
                conn.execute(
                    "INSERT INTO active_objects(object_id,tenant_id,service_id,vault_id,generation) VALUES(?,?,?,?,?)",
                    (staged["object_id"], *key),
                )
                conn.execute("DELETE FROM staged_uploads WHERE upload_id=?", (upload_id,))
                conn.commit()
            except Exception:
                conn.rollback()
                raise

    def delete_generation(
        self,
        *,
        tenant_id: str,
        service_id: str,
        vault_id: str,
        generation: str,
    ) -> DeleteStatus:
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                self._reconcile_tombstones_in_transaction(conn)
                existing = self._ledger_tombstone(conn, tenant_id, service_id, vault_id, generation)
                if existing is not None:
                    state = self._effective_delete_state(
                        conn, tenant_id, service_id, vault_id, generation
                    )
                    conn.commit()
                    return DeleteStatus(
                        tenant_id,
                        service_id,
                        vault_id,
                        generation,
                        existing["delete_operation_id"],
                        state,
                    )

                provision = conn.execute(
                    """SELECT state FROM provisionings
                       WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?""",
                    (tenant_id, service_id, vault_id, generation),
                ).fetchone()
                if provision is None or provision["state"] != "PROVISIONED":
                    raise CapabilityUnavailable("DELETE provisioning is absent or revoked")

                generation_row = conn.execute(
                    "SELECT tombstoned FROM vault_generations WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                    (tenant_id, service_id, vault_id, generation),
                ).fetchone()
                if generation_row is None:
                    raise StoreNotFound("generation not found")

                delete_operation_id = self.delete_operation_id_factory()
                now = self.now()
                conn.execute(
                    """INSERT INTO ledger.tombstones(
                        tenant_id,service_id,vault_id,generation,delete_operation_id,state,created_at,updated_at
                    ) VALUES(?,?,?,?,?,'PENDING',?,?)""",
                    (tenant_id, service_id, vault_id, generation, delete_operation_id, now, now),
                )
                conn.execute(
                    "UPDATE vault_generations SET tombstoned=1 WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                    (tenant_id, service_id, vault_id, generation),
                )
                conn.execute(
                    """INSERT INTO purge_outbox(
                        tenant_id,service_id,vault_id,generation,delete_operation_id,state
                    ) VALUES(?,?,?,?,?,'PENDING')""",
                    (tenant_id, service_id, vault_id, generation, delete_operation_id),
                )
                conn.commit()
                return DeleteStatus(tenant_id, service_id, vault_id, generation, delete_operation_id, "PENDING")
            except Exception:
                conn.rollback()
                raise

    def run_purge_once(self, *, max_objects_per_generation: int | None = None) -> list[DeleteStatus]:
        if max_objects_per_generation is not None and max_objects_per_generation < 0:
            raise ValueError("max_objects_per_generation must be >= 0")
        results: list[DeleteStatus] = []
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                rows = conn.execute("SELECT * FROM purge_outbox WHERE state='PENDING' ORDER BY rowid").fetchall()
                for row in rows:
                    key = (row["tenant_id"], row["service_id"], row["vault_id"], row["generation"])
                    limit = max_objects_per_generation
                    if limit is None:
                        conn.execute(
                            "DELETE FROM active_objects WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                            key,
                        )
                    elif limit > 0:
                        object_rows = conn.execute(
                            """SELECT object_id FROM active_objects
                               WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?
                               ORDER BY object_id LIMIT ?""",
                            (*key, limit),
                        ).fetchall()
                        conn.executemany(
                            "DELETE FROM active_objects WHERE object_id=?",
                            [(r["object_id"],) for r in object_rows],
                        )

                    remaining = conn.execute(
                        "SELECT COUNT(*) AS n FROM active_objects WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                        key,
                    ).fetchone()["n"]
                    if remaining == 0:
                        conn.execute(
                            "UPDATE purge_outbox SET state='COMPLETE' WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                            key,
                        )
                        conn.execute(
                            """UPDATE ledger.tombstones SET state='COMPLETE', updated_at=?
                               WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?""",
                            (self.now(), *key),
                        )
                        state = "COMPLETE"
                    else:
                        state = "PENDING"
                    results.append(DeleteStatus(*key, row["delete_operation_id"], state))
                conn.commit()
                return results
            except Exception:
                conn.rollback()
                raise

    def reconcile_tombstones(self) -> None:
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                self._reconcile_tombstones_in_transaction(conn)
                conn.commit()
            except Exception:
                conn.rollback()
                raise

    def read_object(self, *, object_id: str) -> str:
        with closing(self._connect()) as conn:
            row = conn.execute("SELECT * FROM active_objects WHERE object_id=?", (object_id,)).fetchone()
            if row is None:
                raise StoreNotFound("active object not found")
            if self._ledger_tombstone(
                conn, row["tenant_id"], row["service_id"], row["vault_id"], row["generation"]
            ):
                raise Tombstoned("tombstoned generation is not readable")
            return row["object_id"]

    def administrative_restore_object(
        self,
        *,
        object_id: str,
        tenant_id: str,
        service_id: str,
        vault_id: str,
        generation: str,
    ) -> None:
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                self._require_live_generation(conn, tenant_id, service_id, vault_id, generation)
                conn.execute(
                    "INSERT INTO active_objects(object_id,tenant_id,service_id,vault_id,generation) VALUES(?,?,?,?,?)",
                    (object_id, tenant_id, service_id, vault_id, generation),
                )
                conn.commit()
            except Exception:
                conn.rollback()
                raise

    def active_object_count(self, *, tenant_id: str, service_id: str, vault_id: str, generation: str) -> int:
        with closing(self._connect()) as conn:
            return int(conn.execute(
                "SELECT COUNT(*) AS n FROM active_objects WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                (tenant_id, service_id, vault_id, generation),
            ).fetchone()["n"])

    def generation_tombstoned(self, *, tenant_id: str, service_id: str, vault_id: str, generation: str) -> bool:
        with closing(self._connect()) as conn:
            if self._ledger_tombstone(conn, tenant_id, service_id, vault_id, generation):
                return True
            row = conn.execute(
                "SELECT tombstoned FROM vault_generations WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                (tenant_id, service_id, vault_id, generation),
            ).fetchone()
            return bool(row and row["tombstoned"])

    def _effective_delete_state(
        self,
        conn: sqlite3.Connection,
        tenant_id: str,
        service_id: str,
        vault_id: str,
        generation: str,
    ) -> str:
        key = (tenant_id, service_id, vault_id, generation)
        active_count = conn.execute(
            "SELECT COUNT(*) AS n FROM active_objects WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
            key,
        ).fetchone()["n"]
        outbox = conn.execute(
            "SELECT state FROM purge_outbox WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
            key,
        ).fetchone()
        if active_count or outbox is None or outbox["state"] != "COMPLETE":
            return "PENDING"
        return "COMPLETE"

    def _require_live_generation(self, conn: sqlite3.Connection, tenant_id: str, service_id: str, vault_id: str, generation: str) -> None:
        if self._ledger_tombstone(conn, tenant_id, service_id, vault_id, generation):
            raise Tombstoned("generation has durable tombstone")
        row = conn.execute(
            "SELECT tombstoned FROM vault_generations WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
            (tenant_id, service_id, vault_id, generation),
        ).fetchone()
        if row is None:
            raise StoreNotFound("generation not found")
        if row["tombstoned"]:
            raise Tombstoned("generation tombstoned")

    def _ledger_tombstone(self, conn: sqlite3.Connection, tenant_id: str, service_id: str, vault_id: str, generation: str):
        return conn.execute(
            """SELECT * FROM ledger.tombstones
               WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?""",
            (tenant_id, service_id, vault_id, generation),
        ).fetchone()

    def _reconcile_tombstones_in_transaction(self, conn: sqlite3.Connection) -> None:
        rows = conn.execute("SELECT * FROM ledger.tombstones").fetchall()
        for row in rows:
            key = (row["tenant_id"], row["service_id"], row["vault_id"], row["generation"])
            conn.execute(
                """INSERT INTO vault_generations(tenant_id,service_id,vault_id,generation,tombstoned)
                   VALUES(?,?,?,?,1)
                   ON CONFLICT(tenant_id,service_id,vault_id,generation)
                   DO UPDATE SET tombstoned=1""",
                key,
            )
            active_count = conn.execute(
                "SELECT COUNT(*) AS n FROM active_objects WHERE tenant_id=? AND service_id=? AND vault_id=? AND generation=?",
                key,
            ).fetchone()["n"]
            reconciled_state = "PENDING" if active_count else row["state"]
            conn.execute(
                """INSERT INTO purge_outbox(tenant_id,service_id,vault_id,generation,delete_operation_id,state)
                   VALUES(?,?,?,?,?,?)
                   ON CONFLICT(tenant_id,service_id,vault_id,generation)
                   DO UPDATE SET delete_operation_id=excluded.delete_operation_id, state=excluded.state""",
                (*key, row["delete_operation_id"], reconciled_state),
            )

    @staticmethod
    def _provisioning_from_row(row: sqlite3.Row) -> ProvisioningRow:
        return ProvisioningRow(
            tenant_id=row["tenant_id"],
            service_id=row["service_id"],
            operation_id=row["operation_id"],
            vault_id=row["vault_id"],
            generation=row["generation"],
            request_digest=row["request_digest"],
            verifier_version=row["verifier_version"],
            verifier_hex=row["verifier_hex"],
            state=row["state"],
        )

    @staticmethod
    def _delete_status_from_row(row: sqlite3.Row) -> DeleteStatus:
        return DeleteStatus(
            tenant_id=row["tenant_id"],
            service_id=row["service_id"],
            vault_id=row["vault_id"],
            generation=row["generation"],
            delete_operation_id=row["delete_operation_id"],
            state=row["state"],
        )
