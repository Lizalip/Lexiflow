"""Isolated, non-production SQLite experiment for LexiFlow."""
from __future__ import annotations
import sqlite3
import unicodedata
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

SCHEMA = Path(__file__).with_name('schema.sql')
NORMALIZATION_VERSION = 'nfc-casefold-nfc-python-whitespace-v1'


def normalize(text: str) -> str:
    """Preserve ё, apostrophes, hyphens and original spelling in source fields."""
    if not isinstance(text, str):
        raise TypeError('expected string')
    if any(0xD800 <= ord(c) <= 0xDFFF or c == '\x00' for c in text):
        raise ValueError('invalid Unicode scalar or NUL')
    return ' '.join(unicodedata.normalize('NFC', unicodedata.normalize('NFC', text).casefold()).split())


def prefix_end(prefix: str) -> str | None:
    """Exclusive upper bound for UTF-8 BINARY ordering; skip surrogates."""
    for i in range(len(prefix) - 1, -1, -1):
        code = ord(prefix[i])
        if code < 0x10FFFF:
            return prefix[:i] + chr(0xE000 if code == 0xD7FF else code + 1)
    return None


def snapshot_id(source: str, digest: str, locator: str) -> str:
    """Stable for the SAME snapshot only, not an inter-release identity registry."""
    import json
    return str(uuid5(NAMESPACE_URL, json.dumps([source, digest, locator], ensure_ascii=True)))


def create_database(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA.read_text(encoding='utf-8'))
    if conn.execute('PRAGMA foreign_keys').fetchone()[0] != 1:
        raise RuntimeError('foreign keys disabled')
    with conn:
        conn.executemany('INSERT INTO metadata VALUES (?,?)', [
            ('purpose','experimental-not-for-distribution'),
            ('normalization',NORMALIZATION_VERSION),
            ('unicode_version',unicodedata.unidata_version),
            ('identity','snapshot-local-uuid5'),
        ])
        conn.executemany('INSERT INTO language VALUES (?)', [('en',),('ru',),('und',)])


def _query(direction: str, text: str, limit: int, exact: bool, release_safe: bool = False) -> tuple[str, tuple]:
    if direction not in ('en-ru','ru-en'):
        raise ValueError('direction must be en-ru or ru-en')
    if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 1000:
        raise ValueError('limit must be 1..1000')
    if isinstance(text, str) and len(text) > 256:
        raise ValueError('query exceeds 256 characters')
    key = normalize(text)
    if not key:
        return '', ()
    forward = direction == 'en-ru'
    col = 'e.search_key' if forward else 't.search_key'
    lang = 'e.lang' if forward else 't.lang'
    select = '''SELECT e.id, e.spelling, e.pos, s.id AS sense_id, s.position,
        NULL AS matched_translation, ev.rights AS rights, e.search_key AS matched_key
        FROM entry e JOIN evidence ev ON ev.id=e.evidence_id
        LEFT JOIN sense s ON s.entry_id=e.id
            AND EXISTS (SELECT 1 FROM evidence se WHERE se.id=s.evidence_id AND se.rights!='excluded')''' if forward else '''SELECT e.id, e.spelling, e.pos, s.id AS sense_id, s.position,
        t.wording AS matched_translation, ev.rights AS rights, t.search_key AS matched_key
        FROM translation t JOIN evidence ev ON ev.id=t.evidence_id
        JOIN sense s ON s.id=t.sense_id JOIN entry e ON e.id=s.entry_id
        JOIN evidence se ON se.id=s.evidence_id JOIN evidence ee ON ee.id=e.evidence_id'''
    params: list = ['en' if forward else 'ru']
    where = f'{lang}=?'
    if not forward:
        where += " AND e.lang='en' AND ev.rights='cleared' AND se.rights='cleared' AND ee.rights='cleared'"
    elif release_safe:
        where += """ AND ev.rights='cleared' AND EXISTS
            (SELECT 1 FROM evidence se WHERE se.id=s.evidence_id AND se.rights='cleared')"""
    else:
        where += " AND ev.rights!='excluded'"
    if exact:
        where += f' AND {col}=?'; params.append(key)
    else:
        where += f' AND {col}>=?'; params.append(key)
        end = prefix_end(key)
        if end is not None:
            where += f' AND {col}<?'; params.append(end)
    hits = f'{select} WHERE {where}'
    if forward:
        form_select = select.replace('e.search_key AS matched_key', 'f.search_key AS matched_key')
        form_select = form_select.replace('FROM entry e', 'FROM form f CROSS JOIN entry e ON e.id=f.entry_id')
        form_select += ' JOIN evidence fe ON fe.id=f.evidence_id'
        form_rights = "fe.rights='cleared'" if release_safe else "fe.rights!='excluded'"
        hits += f" UNION ALL {form_select} WHERE {where.replace('e.search_key','f.search_key')} AND {form_rights}"
        params.extend(params[:])
    sql = f'''WITH hits AS ({hits}), ranked AS (
        SELECT *, row_number() OVER (PARTITION BY id,sense_id
            ORDER BY (matched_key=?) DESC, matched_key COLLATE BINARY, matched_translation COLLATE BINARY) AS rank
        FROM hits
    ) SELECT id, spelling, pos, sense_id, position, matched_translation, rights, matched_key
        FROM ranked WHERE rank=1
        ORDER BY (matched_key=?) DESC, matched_key COLLATE BINARY, id, position, sense_id LIMIT ?'''
    params.extend([key, key, limit])
    return sql, tuple(params)


def _search(conn, direction, text, limit, exact, release_safe):
    sql, params = _query(direction,text,limit,exact,release_safe)
    if not sql:
        return []
    cur = conn.execute(sql,params)
    names = [d[0] for d in cur.description]
    return [dict(zip(names,row)) for row in cur]


def research_lookup(conn, direction, text, *, limit=50, exact=False):
    """Internal English candidates may still have pending evidence."""
    return _search(conn, direction, text, limit, exact, False)


def release_lookup(conn, direction, text, *, limit=50, exact=False):
    """Return only cleared entry/sense hits, and cleared translation text for reverse lookup."""
    return _search(conn, direction, text, limit, exact, True)


search = research_lookup


def query_plan(conn: sqlite3.Connection, direction: str, text: str, *, release_safe=False) -> list[str]:
    sql, params = _query(direction,text,50,False,release_safe)
    return [r[3] for r in conn.execute('EXPLAIN QUERY PLAN '+sql,params)] if sql else []


def integrity(conn: sqlite3.Connection) -> None:
    if conn.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
        raise RuntimeError('integrity check failed')
    if conn.execute('PRAGMA foreign_key_check').fetchone() is not None:
        raise RuntimeError('foreign key check failed')
