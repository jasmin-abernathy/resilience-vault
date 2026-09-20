"""Executable specification only: no Android receiver, real keys or deletion.

The shared lock models ONE durable serializable transaction, not a production
storage implementation. Fake effect flags survive simulated process restarts.
"""
from dataclasses import dataclass, field
from hashlib import sha256
from hmac import compare_digest
import re
from threading import RLock

HOUR = 3_600_000
DURATIONS = {n * HOUR for n in (1, 6, 12, 24, 48, 72)}
DRIFT = 2_000  # detection tolerance, NEVER added to the monotonic deadline
NUMBER = re.compile(r"\+[1-9][0-9]{6,14}\Z", re.ASCII)
TOKEN = re.compile(r"[0-9a-f]{64}\Z", re.ASCII)


@dataclass(frozen=True)
class Clock:
    boot: str | None
    elapsed: int
    wall: int


@dataclass(repr=False)
class Arm:
    generation: str
    start: Clock
    duration: int
    verifiers: dict


@dataclass(repr=False)
class Store:
    lock: object = field(default_factory=RLock)
    arm: Arm | None = None
    phase: str = "IDLE"
    unavailable: bool = False
    writable: bool = True
    keys_present: bool = True
    access_locked: bool = False
    purged: bool = False
    revoked: bool = False
    remote_deleted: bool = False
    accepted: int = 0
    events: list = field(default_factory=list)


def verifier(generation, number, token):
    # Tokens are 256-bit random values supplied by the future CSPRNG adapter.
    # Fixed grammar excludes separators; bind verification to contact + arming.
    return sha256(("RV1\0" + generation + "\0" + number + "\0" + token).encode("ascii")).digest()


class Model:
    def __init__(self, store):
        self.s = store

    def arm_local(self, generation, contacts, duration, now, permission=True):
        """Trusted LOCAL entry point; fixture secrets injected only for tests.

        Production must generate fresh generation + secrets, never accept them
        from UI/contact/SMS. An invalid local rearm disarms the previous window.
        """
        with self.s.lock:
            self.s.arm = None
            if self.s.unavailable or not self.s.writable or self.s.phase != "IDLE":
                return False
            numbers = [n for n, _ in contacts]
            tokens = [t for _, t in contacts]
            if (not permission or not now.boot or now.elapsed < 0 or now.wall < 0
                    or duration not in DURATIONS or not TOKEN.fullmatch(generation)
                    or not 1 <= len(contacts) <= 5 or len(set(numbers)) != len(numbers)
                    or len(set(tokens)) != len(tokens)
                    or any(not NUMBER.fullmatch(n) for n in numbers)
                    or any(not TOKEN.fullmatch(t) for t in tokens)):
                return False
            self.s.arm = Arm(generation, now, duration,
                             {n: verifier(generation, n, t) for n, t in contacts})
            return True

    def disarm_local(self):
        with self.s.lock:
            self.s.arm = None  # cannot cancel a committed panic

    def remove_local(self, number):
        with self.s.lock:
            if self.s.arm:
                self.s.arm.verifiers.pop(number, None)
                if not self.s.arm.verifiers:
                    self.s.arm = None

    def _valid_window(self, now, permission):
        arm = self.s.arm
        if arm is None:
            return False
        delta = now.elapsed - arm.start.elapsed
        valid = (permission and now.boot is not None and now.boot == arm.start.boot
                 and 0 <= delta < arm.duration
                 and now.wall < arm.start.wall + arm.duration
                 and abs((now.wall - arm.start.wall) - delta) <= DRIFT)
        if not valid:
            self.s.arm = None
        return valid

    def receive(self, sender, body, now, permission=True):
        """Only a complete, trusted-system-delivered ASCII command enters here.

        No raw PDU parsing or phone-number normalization is modeled.
        """
        with self.s.lock:
            if self.s.unavailable or not self.s.writable:
                self.s.access_locked = True
                return False
            if self.s.phase != "IDLE" or not self._valid_window(now, permission):
                return False
            arm = self.s.arm
            if sender not in arm.verifiers or not isinstance(body, str):
                return False
            parts = body.split(" ")
            if (len(parts) != 3 or parts[0] != "RV1" or parts[1] != arm.generation
                    or not TOKEN.fullmatch(parts[2])):
                return False
            if not compare_digest(arm.verifiers[sender], verifier(parts[1], sender, parts[2])):
                return False
            # SINGLE commit: consume all secrets AND persist a recovery intent.
            self.s.arm = None
            self.s.phase = "LOCAL_PENDING"
            self.s.access_locked = True
            self.s.accepted += 1
            return True

    def panic_local(self):
        with self.s.lock:
            if self.s.unavailable or not self.s.writable:
                self.s.access_locked = True
                return False
            if self.s.phase != "IDLE":
                return False
            self.s.arm = None
            self.s.phase = "LOCAL_PENDING"
            self.s.access_locked = True
            self.s.accepted += 1
            return True

    def can_open(self):
        with self.s.lock:
            return not (self.s.unavailable or self.s.access_locked or self.s.phase != "IDLE")

    def resume(self, crash=None, remote="ok", fail=None):
        """Simulated effects. Crash points cover both sides of effect/checkpoint.

        Post-local tasks are independently retryable, do not gate each other,
        and never re-open access. A permanent cloud error remains pending.
        """
        with self.s.lock:
            if self.s.unavailable or self.s.phase == "IDLE":
                return
            self.s.access_locked = True
            if not self.s.writable:
                return
            if self.s.phase == "LOCAL_PENDING":
                if crash == "before_keys" or fail == "keys":
                    return
                self.s.keys_present = False
                self.s.events.append("destroy_keys")
                if crash == "after_keys":
                    return
                self.s.phase = "POST_PENDING"
                if crash == "after_local_checkpoint":
                    return
            if self.s.phase == "POST_PENDING":
                for name in ("purged", "revoked"):
                    if fail != name:
                        setattr(self.s, name, True)
                if not self.s.remote_deleted and remote == "ok":
                    self.s.events.append("remote_delete")
                    if crash == "after_remote_effect":
                        return
                    self.s.remote_deleted = True
                if self.s.purged and self.s.revoked and self.s.remote_deleted:
                    self.s.phase = "COMPLETE"


def assembled_command(parts, complete, consistent):
    """Adapter contract test double, NOT a PDU completeness validator.

    Android must establish completeness/order/common sender/SIM from its input;
    an unproven assembly must fail closed. Never join across broadcasts.
    """
    if not complete or not consistent or not 1 <= len(parts) <= 4:
        return None
    if any(not isinstance(p, str) or not p for p in parts):
        return None
    body = "".join(parts)
    if not body.isascii() or len(body) != 133:
        return None
    return body
