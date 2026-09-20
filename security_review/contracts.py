"""Executable design model, NOT application code or a cryptographic implementation."""
from dataclasses import dataclass
from enum import Enum
import struct

HEADER = struct.Struct('>4sBBH16s16sQQQ')
LIMITS = {1: 1 << 30, 2: 8 << 20, 3: 64 << 10, 4: 64 << 10}


def header(kind, vault, record, epoch, revision, length):
    if kind not in LIMITS or len(vault) != 16 or len(record) != 16:
        raise ValueError('invalid identity or kind')
    if not 1 <= epoch < 1 << 63 or not 1 <= revision < 1 << 63:
        raise ValueError('invalid epoch or revision')
    if not 0 <= length <= LIMITS[kind]:
        raise ValueError('length exceeds policy')
    return HEADER.pack(b'RVLT', 1, kind, 1 if kind == 1 else 2,
                       vault, record, epoch, revision, length)


def validate_header(encoded, expected):
    if len(encoded) != HEADER.size:
        raise ValueError('invalid header size')
    magic, version, kind, suite, vault, record, epoch, revision, length = HEADER.unpack(encoded)
    if magic != b'RVLT' or version != 1:
        raise ValueError('unknown format')
    rebuilt = header(kind, vault, record, epoch, revision, length)
    if encoded != rebuilt or encoded != expected:
        raise ValueError('suite or expected context mismatch')
    return length


def accept_checkpoint(known_revision, known_digest, revision, digest, chain_verified):
    """Within an already authenticated vault/epoch; no first-device freshness claim."""
    return (revision == known_revision and digest == known_digest) or (
        revision > known_revision and chain_verified)


class State(Enum):
    ARMED = 0
    INTENT = 1
    KEYS_GONE = 2
    LOCAL_CLEAN = 3
    DELETE_PENDING = 4
    REMOTE_BLOCKED = 5
    COMPLETE = 6
    QUARANTINED = 7


class Crash(BaseException):
    pass


class StorageFailure(Exception):
    pass


@dataclass
class Disk:
    state: State = State.ARMED
    keys: bool = True
    staging: bool = True
    capsule: bool = True
    distant: bool = True


class PanicModel:
    """Sequential durable effects under a SHARED exclusive lifecycle lock.

    'cut' simulates process death after an effect and before its next checkpoint.
    Every new instance is a reboot; Disk is the persisted store. No Android I/O.
    """
    def __init__(self, disk, cut=None, fail_write=None, destroy_fails=False):
        self.disk = disk
        self.cut = cut
        self.fail_write = fail_write
        self.destroy_fails = destroy_fails
        self.events = []
        self.closed = False

    def effect(self, name):
        self.events.append(name)
        if len(self.events) == self.cut:
            raise Crash()

    def write(self, state):
        if state == self.fail_write:
            raise StorageFailure()
        self.disk.state = state
        self.effect('persist_' + state.name)

    def destroy(self):
        if self.destroy_fails:
            raise StorageFailure()
        self.disk.keys = False
        self.effect('destroy_keys')

    def run(self, response='deleted'):
        self.closed = True  # Internal access barrier, not a UI callback.
        d = self.disk
        if not isinstance(d.state, State) or (d.state == State.ARMED and not d.keys):
            self.destroy()
            self.write(State.QUARANTINED)
            return
        if d.state == State.QUARANTINED:
            self.destroy()
            return
        if d.state == State.ARMED:
            try:
                self.write(State.INTENT)
            except StorageFailure:
                self.destroy()
                raise
        if d.state == State.INTENT:
            self.destroy()
            self.write(State.KEYS_GONE)
        if d.state == State.KEYS_GONE:
            d.staging = False
            self.effect('purge_local')
            self.write(State.LOCAL_CLEAN)
        if d.state == State.LOCAL_CLEAN:
            self.write(State.DELETE_PENDING if d.distant else State.COMPLETE)
        if d.state == State.DELETE_PENDING:
            if not d.capsule:
                self.write(State.REMOTE_BLOCKED)
                return
            assert not d.keys and not d.staging
            self.effect('network_delete')
            if response == 'deleted':
                self.write(State.COMPLETE)
            elif response not in ('offline', 'timeout', 'accepted', 'rate_limited', 'server_error'):
                self.write(State.REMOTE_BLOCKED)
        if d.state == State.COMPLETE:
            d.capsule = False
            self.effect('erase_capsule')


class DeleteServiceModel:
    """Authorization and tombstone contract only. Fake symbolic tokens, no HTTP."""
    def __init__(self):
        self.tokens = {'delete-a': ('vault-a', 1, 'operation-a')}
        self.tombstones = set()
        self.terminal_receipts = set()

    def request(self, token, method, vault, generation, operation, purge_complete=True):
        identity = (vault, generation, operation)
        if method != 'DELETE' or self.tokens.get(token) != identity:
            return 'denied'
        self.tombstones.add((vault, generation))
        if purge_complete:
            self.terminal_receipts.add(identity)
        return 'deleted' if identity in self.terminal_receipts else 'accepted'

    def revoke(self, token):
        self.tokens.pop(token, None)

    def may_read_or_write(self, vault, generation):
        return (vault, generation) not in self.tombstones
