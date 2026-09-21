"""Executable server contract; in-memory model, NOT backend authorization code."""
import hashlib
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor


class ServerModel:
    def __init__(self):
        self.lock = threading.Lock()
        self.records = {}
        self.capabilities = {}

    def provision(self, generation, capability):
        with self.lock:
            if generation in self.records:
                raise ValueError("immutable generation already exists")
            self.records[generation] = {"tombstone": False, "complete": False, "objects": set()}
            self.capabilities[hashlib.sha256(capability).digest()] = generation

    def delete_only(self, capability, method, generation):
        with self.lock:
            if method != "DELETE" or self.capabilities.get(hashlib.sha256(capability).digest()) != generation:
                return "DENIED"
            record = self.records[generation]
            record["tombstone"] = True
            return "COMPLETE" if record["complete"] else "PENDING"

    def begin_upload_as_writer(self, generation, object_id):
        with self.lock:
            if self.records[generation]["tombstone"]:
                return None
            return generation, object_id

    def finalize_upload_as_writer(self, ticket):
        with self.lock:
            generation, object_id = ticket
            record = self.records[generation]
            if record["tombstone"]:
                return False
            record["objects"].add(object_id)
            return True

    def purge_worker(self, generation, fail=False):
        with self.lock:
            record = self.records[generation]
            if record["tombstone"] and not fail:
                record["objects"].clear()
                record["complete"] = True


class CloudContractTests(unittest.TestCase):
    def setUp(self):
        self.server = ServerModel()
        self.token = b"synthetic-delete-capability-not-production"
        self.server.provision("generation-one", self.token)

    def test_delete_capability_never_authorizes_other_methods(self):
        for method in ("GET", "HEAD", "LIST", "RESTORE", "POST", "PUT", "PATCH", "ROTATE", "CREATE"):
            self.assertEqual("DENIED", self.server.delete_only(self.token, method, "generation-one"))

    def test_unknown_token_and_wrong_generation_are_denied(self):
        self.assertEqual("DENIED", self.server.delete_only(b"wrong", "DELETE", "generation-one"))
        self.assertEqual("DENIED", self.server.delete_only(self.token, "DELETE", "other"))

    def test_upload_started_before_tombstone_cannot_publish_after(self):
        ticket = self.server.begin_upload_as_writer("generation-one", "obj")
        self.server.delete_only(self.token, "DELETE", "generation-one")
        self.assertFalse(self.server.finalize_upload_as_writer(ticket))

    def test_purge_failure_is_pending_and_retry_is_idempotent(self):
        self.assertEqual("PENDING", self.server.delete_only(self.token, "DELETE", "generation-one"))
        self.server.purge_worker("generation-one", fail=True)
        self.assertEqual("PENDING", self.server.delete_only(self.token, "DELETE", "generation-one"))
        self.server.purge_worker("generation-one")
        for _ in range(3):
            self.assertEqual("COMPLETE", self.server.delete_only(self.token, "DELETE", "generation-one"))
        self.assertIsNone(self.server.begin_upload_as_writer("generation-one", "resurrect"))

    def test_new_generation_is_not_deletable_with_old_token(self):
        self.server.provision("generation-two", b"other-fixture-token")
        self.assertEqual("DENIED", self.server.delete_only(self.token, "DELETE", "generation-two"))

    def test_tombstoned_generation_cannot_be_reprovisioned(self):
        self.server.delete_only(self.token, "DELETE", "generation-one")
        with self.assertRaises(ValueError):
            self.server.provision("generation-one", b"replacement")

    def test_finalize_and_delete_race_never_leaves_published_object_after_purge(self):
        ticket = self.server.begin_upload_as_writer("generation-one", "obj")
        barrier = threading.Barrier(2)
        def writer():
            barrier.wait()
            return self.server.finalize_upload_as_writer(ticket)
        def deleter():
            barrier.wait()
            return self.server.delete_only(self.token, "DELETE", "generation-one")
        with ThreadPoolExecutor(2) as pool:
            w, d = pool.submit(writer), pool.submit(deleter)
            w.result()
            self.assertEqual("PENDING", d.result())
        self.server.purge_worker("generation-one")
        self.assertEqual(set(), self.server.records["generation-one"]["objects"])
        self.assertFalse(self.server.finalize_upload_as_writer(ticket))


if __name__ == "__main__":
    unittest.main()
