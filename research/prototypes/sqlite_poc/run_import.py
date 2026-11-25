"""Local CLI. Output is a new experimental SQLite file; never an app asset."""
import argparse
import json
import os
import sqlite3
from pathlib import Path
from prototype import create_database, query_plan, release_lookup
from kaikki import import_sample
from diagnostics import diagnostic_spellings


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path)
    parser.add_argument('output',type=Path,help='New .sqlite file in an ignored local data directory')
    parser.add_argument('--limit',type=int,default=100)
    parser.add_argument('--method', choices=('first', 'random', 'exact'), default='first')
    parser.add_argument('--seed', type=int, default=0)
    args = parser.parse_args()
    if args.output.suffix.lower() not in ('.sqlite','.db','.sqlite3'):
        parser.error('output must have a database suffix')
    if args.source.resolve() == args.output.resolve():
        parser.error('input and output must differ')
    # Exclusive creation prevents accidental replacement. Parent must already exist.
    fd = os.open(args.output,os.O_CREAT | os.O_EXCL | os.O_WRONLY,0o600)
    os.close(fd)
    try:
        conn = sqlite3.connect(args.output)
        try:
            create_database(conn)
            report = import_sample(
                conn, args.source, limit=args.limit, method=args.method, seed=args.seed,
                targets=diagnostic_spellings() if args.method == 'exact' else None,
            )
            report['integrity_check'] = 'ok'
            report['foreign_key_violations'] = 0
            first = conn.execute('SELECT spelling FROM entry ORDER BY rowid LIMIT 1').fetchone()
            if first:
                report['research_forward_plan'] = query_plan(conn, 'en-ru', first[0][:3])
                report['release_forward_exact_count'] = len(release_lookup(conn, 'en-ru', first[0], exact=True))
            report['reverse_plan'] = query_plan(conn, 'ru-en', '\u0442')
        finally:
            conn.close()
    except BaseException:
        args.output.unlink(missing_ok=True)
        raise
    print(json.dumps(report,indent=2,ensure_ascii=True,sort_keys=True))


if __name__ == '__main__':
    main()
