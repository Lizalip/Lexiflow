"""Local semantic review. Approval and clearance are independent transactions."""
import argparse
import csv
from bisect import insort
from collections import Counter, defaultdict
import hashlib
import json
import sqlite3
from contextlib import closing
from pathlib import Path

from prototype import normalize
from candidate_rank import FORM_TAGS, triage

CLASSES = {'source_gloss', 'candidate_translation', 'definition', 'form-reference',
           'unclear', 'mixed_gloss', 'unsuitable'}
STRUCTURED_SOURCE = 'FreeDict / WikDict / DBnary / Wiktionary'
IDENTITY = ('entry_id', 'sense_id', 'snapshot_sha256', 'locator')
FIELDS = {*IDENTITY, 'classification', 'reason'}
EDITOR_FIELDS = {'proposed_translation', 'decision', 'reviewer', 'method'}
GLOSS_ROWS = """
    SELECT e.id AS entry_id, s.id AS sense_id, r.sha256 AS snapshot_sha256,
           ev.locator, ev.rights, g.body AS gloss, e.spelling AS headword,
           e.pos, s.position AS sense_position, g.evidence_id, g.lang, g.id AS gloss_id,
           src.name AS source,s.raw_tags,
           (SELECT count(*) FROM gloss g2 WHERE g2.sense_id=s.id AND g2.role='source_gloss') AS gloss_count
    FROM gloss g JOIN sense s ON s.id=g.sense_id JOIN entry e ON e.id=s.entry_id
    JOIN evidence ev ON ev.id=g.evidence_id JOIN release r ON r.id=ev.release_id
    JOIN source src ON src.id=r.source_id
    WHERE g.role='source_gloss'
"""
HISTORY = """
CREATE TABLE IF NOT EXISTS semantic_decision(
    decision_id TEXT PRIMARY KEY NOT NULL,
    gloss_id INTEGER NOT NULL UNIQUE REFERENCES gloss(id),
    classification TEXT NOT NULL, reviewer TEXT NOT NULL, method TEXT NOT NULL,
    reason TEXT NOT NULL, translation_id INTEGER UNIQUE REFERENCES translation(id)
);
CREATE TABLE IF NOT EXISTS rights_decision(
    evidence_id INTEGER PRIMARY KEY REFERENCES evidence(id),
    source_url TEXT NOT NULL, license_id TEXT NOT NULL, terms_url TEXT NOT NULL,
    attribution TEXT NOT NULL, modification TEXT NOT NULL,
    decision TEXT NOT NULL CHECK(decision IN ('cleared','excluded')),
    reason TEXT NOT NULL, reviewer TEXT NOT NULL
);
"""


def initialize(conn):
    if conn.in_transaction:
        raise ValueError('initialize review outside a transaction')
    conn.execute('PRAGMA foreign_keys=ON')
    conn.executescript(HISTORY)


def tasks(conn):
    for row in conn.execute(GLOSS_ROWS + ' ORDER BY g.id'):
        task = dict(row)
        task.pop('evidence_id')
        task.pop('gloss_id')
        task.pop('lang')
        task['examples'] = [list(example) for example in conn.execute("""
            SELECT x.body, xt.body FROM example x LEFT JOIN example_translation xt
            ON xt.example_id=x.id WHERE x.sense_id=? ORDER BY x.id,xt.id LIMIT 3
        """, (task['sense_id'],))]
        task['definition_context'] = []
        if task['source'] == STRUCTURED_SOURCE:
            definitions = conn.execute("""SELECT body FROM gloss WHERE sense_id=? AND role='definition'
                                        ORDER BY position""",(task['sense_id'],)).fetchall()
            if not definitions:
                children = conn.execute("""SELECT id FROM sense WHERE entry_id=?
                    AND json_extract(raw_tags,'$.parent_sense_id')=? ORDER BY position""",
                    (task['entry_id'],task['sense_id'])).fetchall()
                if len(children) == 1:
                    definitions = conn.execute("""SELECT body FROM gloss
                        WHERE sense_id=? AND role='definition' ORDER BY position""",(children[0][0],)).fetchall()
            task['definition_context'] = [row[0] for row in definitions]
        task.update(status='pending', classification=None, proposed_translation=None,
                    decision=None, reviewer=None, method=None, reason=None)
        yield task


def classify(task):
    raw = json.loads(task['raw_tags'])
    tags = raw.get('tags',[]) if isinstance(raw,dict) else raw
    form = (isinstance(raw,dict) and raw.get('form_reference',False)) or any(
        isinstance(tag,str) and tag in FORM_TAGS for tag in tags)
    classification, priority, proposed, reason = triage(task['gloss'],form)
    if priority == 'standard' and task['gloss_count'] > 1:
        classification, priority, proposed, reason = (
            'unclear','specialist',None,'multiple gloss rows for the same source sense')
    if task.get('source') == STRUCTURED_SOURCE:
        if priority == 'standard' and len(task['definition_context']) == 1 and task['pos']:
            reason = 'explicit Russian TEI translation with one candidate and one definition for context'
        elif priority == 'standard':
            classification, priority, proposed, reason = (
                'unclear','specialist',None,'verify missing or ambiguous TEI definition or part of speech')
    return dict(task,classification=classification,review_priority=priority,proposed_translation=proposed,
                decision='proposed',method='rule-assisted',reviewer='triage-v1',reason=reason)


def triage_rows(conn, priority=None):
    for task in tasks(conn):
        result = classify(task)
        if priority is None or priority == result['review_priority']:
            yield result


def stratify(rows, limit, seed):
    groups = defaultdict(list)
    for row in rows:
        priority = row['review_priority']
        if priority == 'exclude':
            continue
        group = (priority,row['pos'] or '',bool(row['examples']))
        key = repr((seed,row['snapshot_sha256'],row['locator'])).encode()
        bucket = groups[group]
        insort(bucket,(hashlib.sha256(key).digest(),row),key=lambda item:item[0])
        if len(bucket) > limit:
            bucket.pop()
    queues = [iter(groups[group]) for group in sorted(groups)]
    count = 0
    while queues and count < limit:
        active = []
        for queue in queues:
            row = next(queue,None)
            if row is not None:
                yield row[1]
                count += 1
                if count == limit:
                    return
                active.append(queue)
        queues = active


def csv_cell(value):
    if isinstance(value,(list,dict)):
        value = json.dumps(value,ensure_ascii=False)
    # Spreadsheet apps can interpret leading symbols as formulas.
    if isinstance(value,str) and value.lstrip().startswith(('=','+','-','@')):
        return "'" + value
    return value


def export_triage(conn, path, *, priority=None, limit=None, seed=42):
    rows = triage_rows(conn,priority)
    if limit is not None:
        rows = stratify(rows,limit,seed)
    stats = Counter()
    with path.open('x',encoding='utf-8-sig' if path.suffix == '.csv' else 'utf-8',newline='') as stream:
        writer = None
        for row in rows:
            stats[row['review_priority']] += 1
            if path.suffix == '.csv':
                if writer is None:
                    writer = csv.DictWriter(stream,fieldnames=list(row))
                    writer.writeheader()
                writer.writerow({key:csv_cell(value) for key,value in row.items()})
            else:
                stream.write(json.dumps(row,ensure_ascii=False)+'\n')
    return dict(stats)


def checked(conn, decisions, *, approval=False):
    seen = set()
    for index, decision in enumerate(decisions, 1):
        fields = set(decision) if isinstance(decision, dict) else set()
        if fields not in (FIELDS, FIELDS | EDITOR_FIELDS) or (approval and fields != FIELDS | EDITOR_FIELDS):
            raise ValueError(f'decision {index}: unexpected or missing fields')
        for field in fields - {'proposed_translation'}:
            value = decision[field]
            if not isinstance(value, str) or not normalize(value):
                raise ValueError(f'decision {index}: empty or invalid field')
        if decision['classification'] not in CLASSES:
            raise ValueError(f'decision {index}: invalid classification')
        if EDITOR_FIELDS <= fields:
            status = decision['decision']
            method = decision['method']
            proposed = decision['proposed_translation']
            if status not in ('proposed','approved') or method not in ('human','ai-assisted','rule-assisted'):
                raise ValueError(f'decision {index}: invalid decision or method')
            if status == 'approved' and method != 'human':
                raise ValueError(f'decision {index}: approval requires a human review')
            if proposed is not None and (not isinstance(proposed, str) or not normalize(proposed)):
                raise ValueError(f'decision {index}: invalid proposed translation')
            if proposed is not None and decision['classification'] not in ('candidate_translation','mixed_gloss'):
                raise ValueError(f'decision {index}: classification cannot carry a translation')
            if status == 'approved' and decision['classification'] == 'candidate_translation' and proposed is None:
                raise ValueError(f'decision {index}: candidate approval requires translation text')
            if approval and status != 'approved':
                raise ValueError(f'decision {index}: proposal cannot be applied')
        key = tuple(decision[field] for field in IDENTITY)
        if key in seen:
            raise ValueError(f'decision {index}: duplicate or conflicting classification')
        seen.add(key)
        row = conn.execute('SELECT q.gloss_id,q.evidence_id,q.lang FROM (' + GLOSS_ROWS + ''') q
            WHERE entry_id=? AND sense_id=? AND snapshot_sha256=? AND locator=?''', key).fetchone()
        if row is None:
            raise ValueError(f'decision {index}: provenance or article/sense mismatch')
        yield decision, tuple(row)


def validate(conn, decisions):
    return sum(1 for _ in checked(conn, decisions))


def apply(conn, decisions):
    initialize(conn)
    inserted = 0
    with conn:
        conn.execute('BEGIN IMMEDIATE')
        for decision, (gloss_id, evidence_id, lang) in checked(conn, decisions, approval=True):
            payload = json.dumps(decision, sort_keys=True, ensure_ascii=True)
            decision_id = hashlib.sha256(payload.encode()).hexdigest()
            previous = conn.execute('SELECT decision_id FROM semantic_decision WHERE gloss_id=?', (gloss_id,)).fetchone()
            if previous:
                if previous[0] != decision_id:
                    raise ValueError('conflicting decision for this gloss version')
                continue
            wording = decision['proposed_translation']
            translation_id = None
            if wording is not None:
                if lang != 'ru':
                    raise ValueError('this adapter approves Russian translation candidates only')
                new_evidence = conn.execute("""
                    INSERT INTO evidence(release_id,locator)
                    SELECT release_id,locator||'/editor/'||? FROM evidence WHERE id=?
                """, (decision_id, evidence_id)).lastrowid
                translation_id = conn.execute("""
                    INSERT INTO translation(sense_id,lang,wording,search_key,evidence_id) VALUES (?,?,?,?,?)
                """, (decision['sense_id'],lang,wording,normalize(wording),new_evidence)).lastrowid
            conn.execute('INSERT INTO semantic_decision VALUES (?,?,?,?,?,?,?)',
                         (decision_id,gloss_id,decision['classification'],decision['reviewer'],
                          decision['method'],decision['reason'],translation_id))
            inserted += 1
    return inserted


def suggest(task):
    """Conservative triage proposal for inspection, never an approval."""
    gloss = task['gloss'].strip()
    if gloss.startswith(('форма ', 'мн. ч.', 'прош. вр.')):
        classification, reason = 'form-reference', 'Surface marker of inflection'
    elif ';' in gloss or '◆' in gloss:
        classification, reason = 'mixed_gloss', 'Inspect segments and sense context separately'
    elif len(gloss.split()) <= 3 and not any(mark in gloss for mark in ':(),'):
        classification, reason = 'candidate_translation', 'Short gloss for editorial equivalence check'
    else:
        classification, reason = 'unclear', 'Context or gloss boundaries require manual inspection'
    return {**{key:task[key] for key in IDENTITY}, 'classification':classification,
            'reason':reason, 'proposed_translation':None, 'decision':'proposed',
            'reviewer':'Codex', 'method':'ai-assisted'}


def progress(conn):
    has_history = conn.execute("SELECT 1 FROM sqlite_master WHERE name='semantic_decision'").fetchone()
    return dict(tasks=conn.execute('SELECT count(*) FROM gloss').fetchone()[0],
                approved=conn.execute('SELECT count(*) FROM semantic_decision').fetchone()[0] if has_history else 0,
                rights=dict(conn.execute('SELECT rights,count(*) FROM evidence GROUP BY rights')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('export','suggest','triage','validate','apply','progress'))
    parser.add_argument('database', type=Path)
    parser.add_argument('jsonl', type=Path, nargs='?')
    parser.add_argument('--priority',choices=('standard','specialist','exclude'))
    parser.add_argument('--stratified',type=int,choices=range(1,501),metavar='1..500')
    parser.add_argument('--seed',type=int,default=42)
    args = parser.parse_args()
    data = Path(__file__).resolve().parents[2] / 'data'
    if args.action == 'apply' and not args.database.resolve().is_relative_to((data/'processed').resolve()):
        parser.error('apply requires a local database under research/data/processed/')
    suffixes = ('.jsonl','.csv') if args.action == 'triage' else ('.jsonl',)
    if args.action != 'progress' and (args.jsonl is None or args.jsonl.suffix not in suffixes
            or not args.jsonl.resolve().is_relative_to((data/'review').resolve())):
        parser.error('use a supported file under research/data/review/')
    if args.action != 'triage' and (args.priority is not None or args.stratified is not None):
        parser.error('filters apply to triage only')
    if args.stratified and args.priority == 'exclude':
        parser.error('stratified review excludes form-only or no-content tasks')
    mode = 'rw' if args.action == 'apply' else 'ro'
    with closing(sqlite3.connect(args.database.resolve().as_uri() + '?mode=' + mode, uri=True)) as conn:
        conn.row_factory = sqlite3.Row
        if args.action in ('export','suggest'):
            with args.jsonl.open('x', encoding='utf-8') as stream:
                for task in tasks(conn):
                    row = task if args.action == 'export' else suggest(task)
                    stream.write(json.dumps(row, ensure_ascii=False) + '\n')
        elif args.action == 'progress':
            print(json.dumps(progress(conn)))
        elif args.action == 'triage':
            print(json.dumps(export_triage(conn,args.jsonl,priority=args.priority,limit=args.stratified,seed=args.seed)))
        else:
            with args.jsonl.open(encoding='utf-8') as stream:
                op = apply if args.action == 'apply' else validate
                print(json.dumps({'decisions': op(conn, map(json.loads, stream))}))


if __name__ == '__main__':
    main()
