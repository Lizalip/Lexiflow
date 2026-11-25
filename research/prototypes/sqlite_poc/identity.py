"""Versioned local identity ledger. Cross-snapshot candidates require explicit review."""
import json
from uuid import UUID

SCHEMA = """
CREATE TABLE IF NOT EXISTS identity_binding(
    kind TEXT NOT NULL CHECK(kind IN ('entry','sense')),
    snapshot TEXT NOT NULL, local_id TEXT NOT NULL, persistent_id TEXT NOT NULL,
    reason TEXT NOT NULL, previous_snapshot TEXT, previous_local_id TEXT,
    PRIMARY KEY(kind,snapshot,local_id),
    UNIQUE(snapshot,persistent_id)
);
CREATE TABLE IF NOT EXISTS identity_registry(version INTEGER PRIMARY KEY CHECK(version=1));
INSERT OR IGNORE INTO identity_registry VALUES (1);
"""


def candidates(previous, current):
    """Signals for editorial comparison; source IDs can repeat."""
    matches = []
    for old in previous:
        signals = [field for field in ('source_id','spelling','language','pos','meaning','provenance')
                   if current.get(field) and current[field] == old.get(field)]
        if signals and ('source_id' in signals or 'spelling' in signals or 'meaning' in signals):
            matches.append((old['local_id'], tuple(signals)))
    return sorted(matches, key=lambda item: (-len(item[1]), item[0]))


def bind(conn, kind, snapshot, local_id, persistent_id, *, reason, previous=None):
    if str(UUID(persistent_id)) != persistent_id or not reason.strip():
        raise ValueError('binding requires a canonical UUID and explicit reason')
    if len(snapshot) != 64 or any(c not in '0123456789abcdef' for c in snapshot) or not local_id:
        raise ValueError('invalid snapshot or local identity')
    prior = tuple(previous) if previous else (None,None)
    existing = conn.execute('SELECT persistent_id,reason,previous_snapshot,previous_local_id FROM identity_binding WHERE kind=? AND snapshot=? AND local_id=?',
                            (kind,snapshot,local_id)).fetchone()
    if existing:
        if tuple(existing) != (persistent_id,reason,*prior):
            raise ValueError('local identity already has a reviewed binding')
        return False
    history = conn.execute('SELECT kind,snapshot,local_id FROM identity_binding WHERE persistent_id=?',
                           (persistent_id,)).fetchall()
    if history and (kind, *prior) not in [tuple(row) for row in history]:
        raise ValueError('persistent identity reuse requires an explicit previous binding')
    if not history and previous:
        raise ValueError('previous binding does not exist')
    if any(row[1] == snapshot for row in history):
        raise ValueError('persistent identity cannot identify multiple objects in one snapshot')
    conn.execute('INSERT INTO identity_binding VALUES (?,?,?,?,?,?,?)',
                 (kind,snapshot,local_id,persistent_id,reason,*prior))
    return True


def load_registry(conn, records):
    with conn:
        conn.execute('BEGIN IMMEDIATE')
        return sum(bind(conn, **record) for record in records)


def lookup(conn, kind, snapshot, local_id):
    row = conn.execute('SELECT persistent_id FROM identity_binding WHERE kind=? AND snapshot=? AND local_id=?',
                       (kind,snapshot,local_id)).fetchone()
    if row is None:
        raise ValueError('missing persistent identity for eligible object')
    return row[0]
