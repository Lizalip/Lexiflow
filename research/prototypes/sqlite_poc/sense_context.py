"""Explicit context decisions for one nested source definition."""
import re

TABLE = """CREATE TABLE IF NOT EXISTS sense_context_decision(
    parent_id TEXT PRIMARY KEY REFERENCES sense(id),
    definition_id INTEGER NOT NULL UNIQUE REFERENCES gloss(id),
    reviewer TEXT NOT NULL, reason TEXT NOT NULL
)"""

QUERY = """
    SELECT p.id,g.id,pe.id,oe.id,de.id,pe.locator,oe.locator,de.locator,g.body,g.lang,
           p.entry_id=o.entry_id AND pe.release_id=oe.release_id AND oe.release_id=de.release_id,
           json_extract(o.raw_tags,'$.parent_sense_id')
    FROM gloss g JOIN sense o ON o.id=g.sense_id
    JOIN sense p ON p.id=? JOIN evidence pe ON pe.id=p.evidence_id
    JOIN evidence oe ON oe.id=o.evidence_id JOIN evidence de ON de.id=g.evidence_id
    WHERE g.role='definition'
"""


def direct_child(parent, owner, definition):
    return (bool(re.fullmatch(re.escape(parent)+r'/sense/(0|[1-9][0-9]*)',owner))
            and bool(re.fullmatch(re.escape(owner)+r'/def/(0|[1-9][0-9]*)',definition)))


def check(row):
    parent,_,_,_,_,pl,ol,dl,text,lang,same,declared = row
    if not same or declared != parent or not direct_child(pl,ol,dl) or not text.strip() or lang != 'en':
        raise ValueError('invalid source sense relationship')


def apply_decisions(conn, decisions):
    """Apply supplied human decisions atomically. Rights are recorded separately."""
    conn.execute(TABLE)
    with conn:
        conn.execute('BEGIN IMMEDIATE')
        for record in decisions:
            fields = {'parent_id','snapshot','definition_locator','reviewer','reason','decision','method'}
            if (not isinstance(record,dict) or set(record) != fields
                    or any(not isinstance(v,str) or not v.strip() for v in record.values())
                    or record['decision'] != 'approved' or record['method'] != 'human'):
                raise ValueError('explicit human context approval required')
            rows = conn.execute(QUERY+"""
                AND de.locator=? AND pe.release_id IN (SELECT id FROM release WHERE sha256=?)
                """,(record['parent_id'],record['definition_locator'],record['snapshot'])).fetchall()
            if len(rows) != 1:
                raise ValueError('missing or ambiguous definition')
            row = rows[0]
            check(row)
            values = (row[1],record['reviewer'],record['reason'])
            old = conn.execute('SELECT definition_id,reviewer,reason FROM sense_context_decision WHERE parent_id=?',
                               (row[0],)).fetchone()
            if old and tuple(old) != values:
                raise ValueError('conflicting context decision')
            if not old:
                conn.execute('INSERT INTO sense_context_decision VALUES (?,?,?,?)',(row[0],*values))


def export_context(conn, senses, rights, attribution):
    if not conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sense_context_decision'").fetchone():
        return {}
    context = {}
    sql = QUERY.replace('p.id=?','p.id=decision.parent_id').replace(
        'FROM gloss g','FROM sense_context_decision decision JOIN gloss g ON g.id=decision.definition_id')
    for row in conn.execute(sql):
        parent,_,pe,oe,de,_,_,_,text,lang,_,_ = row
        if parent not in senses or not all(i in rights for i in (pe,oe,de)):
            continue
        check(row)
        context[senses[parent]] = dict(relationship='direct-child',text=text,language=lang,
                                       owner=attribution(oe),definition=attribution(de))
    return context


def validate_context(conn, extension, attrs):
    if (not isinstance(extension,dict) or set(extension) != {'version','senses'}
            or type(extension['version']) is not int or extension['version'] != 1
            or not isinstance(extension['senses'],dict) or not extension['senses']):
        raise ValueError('invalid sense context extension')
    used_owners = set()
    for sense_id,row in extension['senses'].items():
        parent = conn.execute('SELECT attribution_id FROM sense WHERE id=?',(sense_id,)).fetchone()
        if (not parent or not isinstance(row,dict)
                or set(row) != {'relationship','text','language','owner','definition'}
                or row['relationship'] != 'direct-child' or row['language'] != 'en'
                or not isinstance(row['text'],str) or not row['text'].strip()
                or any(not isinstance(row[key],str) or row[key] not in attrs for key in ('owner','definition'))):
            raise ValueError('invalid sense context row')
        p,o,d = (attrs[key] for key in (parent[0],row['owner'],row['definition']))
        if (len({(a['source'],a['snapshot']) for a in (p,o,d)}) != 1
                or not direct_child(p['locator'],o['locator'],d['locator']) or row['owner'] in used_owners):
            raise ValueError('invalid source sense relationship')
        used_owners.add(row['owner'])
