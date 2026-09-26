from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import re
from typing import Mapping


@dataclass(frozen=True)
class ReferenceServerConfig:
    service_id: str
    database_path: Path
    tombstone_ledger_path: Path
    bind_host: str = "127.0.0.1"
    port: int = 0

    @classmethod
    def from_environment(cls, env: Mapping[str, str] | None = None) -> "ReferenceServerConfig":
        values = os.environ if env is None else env
        if values.get("RV_REFERENCE_ENABLE") != "1":
            raise RuntimeError("reference server is disabled unless RV_REFERENCE_ENABLE=1")
        service_id = values.get("RV_REFERENCE_SERVICE_ID", "")
        database_path = values.get("RV_REFERENCE_DB_PATH", "")
        tombstone_path = values.get("RV_REFERENCE_TOMBSTONE_DB_PATH", "")
        if not service_id or not database_path or not tombstone_path:
            raise RuntimeError("reference server configuration is incomplete")
        if not re.fullmatch(r"[a-z0-9][a-z0-9._:@-]{0,63}", service_id):
            raise RuntimeError("reference service id is not canonical")
        main_path = Path(database_path)
        ledger_path = Path(tombstone_path)
        if main_path.absolute() == ledger_path.absolute():
            raise RuntimeError("primary database and tombstone ledger must be distinct files")
        host = values.get("RV_REFERENCE_BIND_HOST", "127.0.0.1")
        if host != "127.0.0.1":
            raise RuntimeError("reference server can bind only to 127.0.0.1")
        try:
            port = int(values.get("RV_REFERENCE_PORT", "0"))
        except ValueError as exc:
            raise RuntimeError("invalid reference server port") from exc
        if not 0 <= port <= 65535:
            raise RuntimeError("invalid reference server port")
        return cls(
            service_id=service_id,
            database_path=main_path,
            tombstone_ledger_path=ledger_path,
            bind_host=host,
            port=port,
        )
