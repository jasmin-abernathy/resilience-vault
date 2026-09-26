from __future__ import annotations

import unittest

from reference_server.config import ReferenceServerConfig


class ConfigTest(unittest.TestCase):
    def test_reference_server_fails_closed_when_required_configuration_is_missing(self) -> None:
        with self.assertRaises(RuntimeError):
            ReferenceServerConfig.from_environment({})
        with self.assertRaises(RuntimeError):
            ReferenceServerConfig.from_environment({"RV_REFERENCE_ENABLE": "1"})

    def test_reference_server_rejects_non_loopback_configuration(self) -> None:
        with self.assertRaises(RuntimeError):
            ReferenceServerConfig.from_environment({
                "RV_REFERENCE_ENABLE": "1",
                "RV_REFERENCE_SERVICE_ID": "test-primary",
                "RV_REFERENCE_DB_PATH": "/tmp/main.sqlite",
                "RV_REFERENCE_TOMBSTONE_DB_PATH": "/tmp/tombstone.sqlite",
                "RV_REFERENCE_BIND_HOST": "0.0.0.0",
            })

    def test_service_id_and_database_paths_must_be_canonical_and_distinct(self) -> None:
        with self.assertRaises(RuntimeError):
            ReferenceServerConfig.from_environment({
                "RV_REFERENCE_ENABLE": "1",
                "RV_REFERENCE_SERVICE_ID": "NOT CANONICAL",
                "RV_REFERENCE_DB_PATH": "/tmp/main.sqlite",
                "RV_REFERENCE_TOMBSTONE_DB_PATH": "/tmp/tombstone.sqlite",
            })
        with self.assertRaises(RuntimeError):
            ReferenceServerConfig.from_environment({
                "RV_REFERENCE_ENABLE": "1",
                "RV_REFERENCE_SERVICE_ID": "test-primary",
                "RV_REFERENCE_DB_PATH": "/tmp/same.sqlite",
                "RV_REFERENCE_TOMBSTONE_DB_PATH": "/tmp/same.sqlite",
            })

    def test_complete_local_configuration_is_accepted_without_production_defaults(self) -> None:
        config = ReferenceServerConfig.from_environment({
            "RV_REFERENCE_ENABLE": "1",
            "RV_REFERENCE_SERVICE_ID": "test-primary",
            "RV_REFERENCE_DB_PATH": "/tmp/main.sqlite",
            "RV_REFERENCE_TOMBSTONE_DB_PATH": "/tmp/tombstone.sqlite",
        })
        self.assertEqual("127.0.0.1", config.bind_host)
        self.assertEqual(0, config.port)


if __name__ == "__main__":
    unittest.main()
