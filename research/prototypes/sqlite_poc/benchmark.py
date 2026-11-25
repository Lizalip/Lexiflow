"""Desktop SQLite benchmark using only generated artificial entries."""
import json
import platform
import sqlite3
import statistics
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory
from time import perf_counter_ns

from prototype import create_database, integrity, normalize, query_plan, release_lookup


def populate(conn, count):
    create_database(conn)
    with conn:
        conn.execute("INSERT INTO source VALUES (1,'artificial-benchmark')")
        conn.execute("INSERT INTO release VALUES (1,1,?,'artificial')", ('0'*64,))
        for index in range(count):
            key = f'artificial-{index:06}'
            translation = 'artificial-ru-' + key[-6:]
            ids = [index * 3 + value for value in (1, 2, 3)]
            conn.executemany("""
                INSERT INTO evidence(id,release_id,locator,rights,review_note)
                VALUES (?,1,?,'cleared','artificial benchmark only')
            """, [(value, f'artificial/{value}') for value in ids])
            conn.execute("INSERT INTO entry VALUES (?,'en',?,?,'fixture',?)",
                         (key, key, normalize(key), ids[0]))
            conn.execute("INSERT INTO sense VALUES (?,?,1,?,'[]')", (key, key, ids[1]))
            conn.execute("""
                INSERT INTO translation(sense_id,lang,wording,search_key,evidence_id)
                VALUES (?,'ru',?,?,?)
            """, (key, translation, normalize(translation), ids[2]))
    integrity(conn)


def measure(path, count):
    with closing(sqlite3.connect(path)) as conn:
        populate(conn, count)
    result = dict(entries=count, size_bytes=path.stat().st_size, queries={})
    for direction, prefix in [('en-ru','artificial-0000'), ('ru-en','artificial-ru-0000')]:
        with closing(sqlite3.connect(path)) as conn:
            def run():
                start = perf_counter_ns()
                rows = release_lookup(conn, direction, prefix, limit=20)
                return (perf_counter_ns() - start) / 1e6, len(rows)
            first, hits = run()
            warm = sorted(run()[0] for _ in range(100))
            result['queries'][direction] = dict(
                first_query_ms=first, warm_median_ms=statistics.median(warm),
                warm_p95_ms=warm[94], hits=hits, plan=query_plan(conn, direction, prefix, release_safe=True),
            )
    return result


if __name__ == '__main__':
    with TemporaryDirectory() as directory:
        report = dict(python=platform.python_version(), sqlite=sqlite3.sqlite_version,
                      os=platform.system(), results=[
                          measure(Path(directory) / f'{count}.sqlite', count)
                          for count in (5000, 20000)
                      ])
        print(json.dumps(report, indent=2))
