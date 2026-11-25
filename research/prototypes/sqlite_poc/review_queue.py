"""Export local gloss review tasks and validate decisions without applying them."""
import argparse
import json
import sqlite3
from contextlib import closing
from pathlib import Path

CLASSES = {'source_gloss', 'candidate_translation', 'definition', 'form-reference', 'unclear'}
GLOSS_ROWS = """
    SELECT e.id AS entry_id, s.id AS sense_id, r.sha256 AS snapshot_sha256,
           ev.locator, ev.rights, g.body AS gloss
    FROM gloss g JOIN sense s ON s.id=g.sense_id JOIN entry e ON e.id=s.entry_id
    JOIN evidence ev ON ev.id=g.evidence_id JOIN release r ON r.id=ev.release_id
"""
IDENTITY = ('entry_id', 'sense_id', 'snapshot_sha256', 'locator')


def validate(conn, decisions):
    seen = set()
    for index, decision in enumerate(decisions, 1):
        if not isinstance(decision, dict) or set(decision) != {*IDENTITY, 'classification', 'reason'}:
            raise ValueError(f'decision {index}: unexpected fields')
        if any(not isinstance(value, str) or not value.strip() for value in decision.values()):
            raise ValueError(f'decision {index}: values must be nonempty text')
        if decision['classification'] not in CLASSES:
            raise ValueError(f'decision {index}: invalid classification')
        key = tuple(decision[field] for field in IDENTITY)
        if key in seen:
            raise ValueError(f'decision {index}: duplicate or conflicting classification')
        seen.add(key)
        if conn.execute(GLOSS_ROWS + '''
            WHERE e.id=? AND s.id=? AND r.sha256=? AND ev.locator=?
        ''', key).fetchone() is None:
            raise ValueError(f'decision {index}: provenance or article/sense mismatch')
    return len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('export', 'validate'))
    parser.add_argument('database', type=Path)
    parser.add_argument('jsonl', type=Path, help='Local file under ignored research/data/review/')
    args = parser.parse_args()
    review_dir = Path(__file__).resolve().parents[2] / 'data' / 'review'
    if not args.jsonl.resolve().is_relative_to(review_dir.resolve()) or args.jsonl.suffix != '.jsonl':
        parser.error('use a .jsonl file under research/data/review/')
    with closing(sqlite3.connect(args.database.resolve().as_uri() + '?mode=ro', uri=True)) as conn:
        conn.row_factory = sqlite3.Row
        if args.action == 'export':
            with args.jsonl.open('x', encoding='utf-8') as stream:
                for row in conn.execute(GLOSS_ROWS + ' ORDER BY g.id'):
                    task = dict(row)
                    task.update(status='pending', classification=None, reason=None)
                    stream.write(json.dumps(task, ensure_ascii=False) + '\n')
        else:
            with args.jsonl.open(encoding='utf-8') as stream:
                print(json.dumps({'validated_decisions': validate(conn, map(json.loads, stream))}))


if __name__ == '__main__':
    main()
