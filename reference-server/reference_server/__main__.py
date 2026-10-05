from __future__ import annotations

from .config import ReferenceServerConfig
from .http_api import ReferenceApi
from .store import ReferenceStore


def main() -> None:
    config = ReferenceServerConfig.from_environment()
    store = ReferenceStore(config.database_path, config.tombstone_ledger_path)
    # Authenticators intentionally default-deny. A real identity/capability provider is not defined.
    api = ReferenceApi(store=store, service_id=config.service_id)
    server = api.create_server(host=config.bind_host, port=config.port)
    host, port = server.server_address
    print(f"Resilience Vault reference server listening on {host}:{port}; authentication defaults to deny")
    server.serve_forever()


if __name__ == "__main__":
    main()
