"""Build and validate an experimental, offline dictionary package."""
import argparse
import hashlib
import json
import sqlite3
from contextlib import closing
from pathlib import Path
from urllib.parse import urlsplit
from uuid import UUID

import identity
from prototype import NORMALIZATION_VERSION, integrity
from review_queue import initialize

FORMAT = 1
DISTRIBUTION = """
PRAGMA user_version=1;
CREATE TABLE entry(id TEXT PRIMARY KEY NOT NULL, spelling TEXT NOT NULL, pos TEXT, search_key TEXT NOT NULL, attribution_id TEXT NOT NULL);
CREATE TABLE sense(id TEXT PRIMARY KEY NOT NULL, entry_id TEXT NOT NULL REFERENCES entry(id), position INTEGER NOT NULL, attribution_id TEXT NOT NULL, UNIQUE(entry_id,position));
CREATE TABLE translation(id INTEGER PRIMARY KEY, sense_id TEXT NOT NULL REFERENCES sense(id), wording TEXT NOT NULL, search_key TEXT NOT NULL, attribution_id TEXT NOT NULL, origin_attribution_id TEXT NOT NULL);
CREATE TABLE form(id INTEGER PRIMARY KEY, entry_id TEXT NOT NULL REFERENCES entry(id), spelling TEXT NOT NULL, search_key TEXT NOT NULL, attribution_id TEXT NOT NULL);
CREATE TABLE example(id INTEGER PRIMARY KEY, sense_id TEXT NOT NULL REFERENCES sense(id), body TEXT NOT NULL, attribution_id TEXT NOT NULL);
CREATE TABLE example_translation(id INTEGER PRIMARY KEY, example_id INTEGER NOT NULL REFERENCES example(id), body TEXT NOT NULL, attribution_id TEXT NOT NULL);
CREATE INDEX entry_search ON entry(search_key,id);
CREATE INDEX translation_search ON translation(search_key,sense_id);
CREATE INDEX form_search ON form(search_key,entry_id);
"""
TABLES = ('entry','sense','translation','form','example','example_translation')
MAX_MANIFEST = 64 * 1024**2
MAX_DATABASE = 256 * 1024**2
METADATA_CHECK = """
    CHECK(length(trim(value)) > 0),
    CHECK((kind IN ('ipa','pronunciation','region') AND entry_id IS NOT NULL AND sense_id IS NULL AND form_id IS NULL)
       OR (kind IN ('definition','source_gloss','style','region','cefr') AND entry_id IS NULL AND sense_id IS NOT NULL AND form_id IS NULL)
       OR (kind='grammar' AND entry_id IS NULL AND sense_id IS NULL AND form_id IS NOT NULL)),
    CHECK(kind!='cefr' OR value IN ('A1','A2','B1','B2','C1','C2'))
"""
METADATA_COLUMNS = """
    id INTEGER PRIMARY KEY, entry_id TEXT REFERENCES entry(id), sense_id TEXT REFERENCES sense(id),
    form_id INTEGER REFERENCES form(id), kind TEXT NOT NULL, value TEXT NOT NULL,
    lang TEXT NOT NULL CHECK(lang IN ('en','ru','und')),
"""
DISTRIBUTION_V2 = DISTRIBUTION + f"""
PRAGMA user_version=2;
CREATE TABLE lexical_metadata(
    {METADATA_COLUMNS} attribution_id TEXT NOT NULL, {METADATA_CHECK}
);
CREATE INDEX metadata_entry ON lexical_metadata(entry_id);
CREATE INDEX metadata_sense ON lexical_metadata(sense_id);
CREATE INDEX metadata_form ON lexical_metadata(form_id);
CREATE INDEX translation_preview ON translation(sense_id,search_key,wording);
CREATE INDEX form_parent ON form(entry_id,id);
CREATE INDEX example_parent ON example(sense_id,id);
CREATE INDEX example_translation_parent ON example_translation(example_id,id);
"""
STAGING_METADATA = f"""
CREATE TABLE IF NOT EXISTS lexical_metadata(
    {METADATA_COLUMNS} evidence_id INTEGER NOT NULL UNIQUE REFERENCES evidence(id), {METADATA_CHECK}
);
"""
RIGHTS_FIELDS = ('source_url','license_id','terms_url','attribution','modification','decision','reason','reviewer')
ATTR_FIELDS = ('source_url','license_id','terms_url','attribution','modification')


def valid_url(value):
    parsed = urlsplit(value)
    return parsed.scheme == 'https' and bool(parsed.hostname) and parsed.username is None


def record_rights(conn, snapshot, locator, **record):
    """Apply a documented owner-supplied decision for one specific snapshot element."""
    if set(record) != set(RIGHTS_FIELDS) or any(not isinstance(v,str) or not v.strip() for v in record.values()):
        raise ValueError('incomplete rights decision')
    if record['decision'] not in ('cleared','excluded') or not all(valid_url(record[k]) for k in ('source_url','terms_url')):
        raise ValueError('invalid clearance decision or URL')
    with conn:
        conn.execute('BEGIN IMMEDIATE')
        rows = conn.execute("""
            SELECT ev.id,ev.rights FROM evidence ev JOIN release r ON r.id=ev.release_id
            WHERE r.sha256=? AND ev.locator=?
        """, (snapshot,locator)).fetchall()
        if len(rows) != 1:
            raise ValueError('rights decision has missing or ambiguous provenance')
        eid, current = rows[0]
        values = tuple(record[field] for field in RIGHTS_FIELDS)
        old = conn.execute('SELECT '+','.join(RIGHTS_FIELDS)+' FROM rights_decision WHERE evidence_id=?',(eid,)).fetchone()
        if old and (tuple(old) != values or current != record['decision']):
            raise ValueError('conflicting rights decision')
        if not old:
            conn.execute('INSERT INTO rights_decision VALUES (?,?,?,?,?,?,?,?,?)', (eid,*values))
            conn.execute('UPDATE evidence SET rights=?,review_note=? WHERE id=?',
                         (record['decision'],record['reason'],eid))


def provenance(conn):
    fields = ','.join('d.' + field for field in ATTR_FIELDS)
    return {eid:dict(zip((*ATTR_FIELDS,'source','snapshot','locator'), rest)) for eid,*rest in conn.execute(f"""
        SELECT ev.id,{fields},src.name,r.sha256,ev.locator FROM evidence ev
        JOIN rights_decision d ON d.evidence_id=ev.id JOIN release r ON r.id=ev.release_id
        JOIN source src ON src.id=r.source_id WHERE ev.rights='cleared' AND d.decision='cleared'
    """)}


CORE_SQL = """
    SELECT e.id,e.spelling,e.pos,e.search_key,e.evidence_id,s.id,s.position,s.evidence_id,
           t.wording,t.search_key,t.evidence_id,g.evidence_id,g.sense_id
    FROM translation t JOIN semantic_decision d ON d.translation_id=t.id AND d.method='human'
    JOIN gloss g ON g.id=d.gloss_id JOIN sense s ON s.id=t.sense_id JOIN entry e ON e.id=s.entry_id
    WHERE t.lang='ru' AND e.lang='en' ORDER BY e.id,s.position,t.id
"""


def approved_core(src,rights):
    for row in src.execute(CORE_SQL):
        e,_,_,_,ee,s,_,se,_,_,te,ge,gloss_sense = row
        if not all(i in rights for i in (ee,se,te,ge)):
            continue
        if gloss_sense != s or len({(rights[i]['source'],rights[i]['snapshot']) for i in (ee,se,te,ge)}) != 1:
            raise ValueError('inconsistent translation provenance')
        yield row


def readiness(src,registry=None):
    tables = {name for name, in src.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    report = dict(
        entries=src.execute('SELECT count(*) FROM entry').fetchone()[0],
        human_translation_decisions=0, approved_with_rights=0, with_identity=0,
        registry_supplied=registry is not None,
    )
    if 'semantic_decision' not in tables:
        return report
    report['human_translation_decisions'] = src.execute("""
        SELECT count(*) FROM semantic_decision WHERE method='human' AND translation_id IS NOT NULL
    """).fetchone()[0]
    if 'rights_decision' not in tables:
        return report
    rights = provenance(src)
    for e,_,_,_,ee,s,_,se,*_ in approved_core(src,rights):
        report['approved_with_rights'] += 1
        if registry is not None:
            keys = [('entry',rights[ee]['snapshot'],e),('sense',rights[se]['snapshot'],s)]
            if all(registry.execute("""
                    SELECT 1 FROM identity_binding WHERE kind=? AND snapshot=? AND local_id=?
                """, key).fetchone() is not None for key in keys):
                report['with_identity'] += 1
    return report


def copy_eligible(src, dest, registry, version):
    rights = provenance(src)
    used = {}

    def attr(eid):
        record = rights[eid]
        ref = hashlib.sha256(json.dumps(record,sort_keys=True).encode()).hexdigest()
        used[ref] = record
        return ref

    entries, senses = {}, {}
    persisted = set()

    def claim(kind,eid,local_id):
        value = identity.lookup(registry,kind,rights[eid]['snapshot'],local_id)
        if value in persisted:
            raise ValueError('duplicate persistent identity')
        persisted.add(value)
        return value
    for e,spelling,pos,ek,ee,s,position,se,word,tk,te,ge,_ in approved_core(src,rights):
        if e not in entries:
            entries[e] = claim('entry',ee,e)
            dest.execute('INSERT INTO entry VALUES (?,?,?,?,?)',(entries[e],spelling,pos,ek,attr(ee)))
        if s not in senses:
            senses[s] = claim('sense',se,s)
            dest.execute('INSERT INTO sense VALUES (?,?,?,?)',(senses[s],entries[e],position,attr(se)))
        dest.execute('INSERT INTO translation(sense_id,wording,search_key,attribution_id,origin_attribution_id) VALUES (?,?,?,?,?)',
                     (senses[s],word,tk,attr(te),attr(ge)))
    if not entries:
        raise ValueError('no eligible entries with an approved and cleared translation')

    examples, forms = {}, {}
    for table, parents, column, fields, lang in (
        ('form',entries,'entry_id','spelling,search_key',None),
        ('example',senses,'sense_id','body','en'),
        ('example_translation',examples,'example_id','body','ru'),
    ):
        clause = f"WHERE lang='{lang}'" if lang else ''
        for row_id,parent,eid,*values in src.execute(
                f'SELECT id,{column},evidence_id,{fields} FROM {table} {clause} ORDER BY id'):
            if parent not in parents or eid not in rights:
                continue
            cols = f'{column},{fields},attribution_id'
            placeholders = ','.join('?' for _ in range(len(values)+2))
            new_id = dest.execute(f'INSERT INTO {table}({cols}) VALUES ({placeholders})',
                                  (parents[parent],*values,attr(eid))).lastrowid
            if table == 'example':
                examples[row_id] = new_id
            elif table == 'form':
                forms[row_id] = new_id
    if version == 2:
        if src.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='lexical_metadata'").fetchone():
            for e,s,f,kind,value,lang,eid in src.execute("""
                    SELECT entry_id,sense_id,form_id,kind,value,lang,evidence_id
                    FROM lexical_metadata ORDER BY id
            """):
                parents = [(entries,e),(senses,s),(forms,f)]
                if eid not in rights or any(key is not None and key not in mapping for mapping,key in parents):
                    continue
                dest.execute("""
                    INSERT INTO lexical_metadata(entry_id,sense_id,form_id,kind,value,lang,attribution_id)
                    VALUES (?,?,?,?,?,?,?)
                """, (*(mapping.get(key) for mapping,key in parents),kind,value,lang,attr(eid)))
    return used


def checksum(path):
    with path.open('rb') as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
        return digest.hexdigest()


def export_package(src, registry, package, corpus_version, *, version=1):
    if not isinstance(corpus_version,str) or not corpus_version.strip():
        raise ValueError('corpus version is required')
    schema, table_names = package_schema(version)
    tables = {name for name, in src.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if not {'semantic_decision','rights_decision'} <= tables:
        raise ValueError('no eligible entries: review and rights decisions are absent')
    package.mkdir()
    database, manifest = package/'dictionary.sqlite', package/'manifest.json'
    try:
        with closing(sqlite3.connect(database)) as dest:
            dest.execute('PRAGMA foreign_keys=ON')
            dest.executescript(schema)
            with dest:
                attributions = copy_eligible(src,dest,registry,version)
            integrity(dest)
            counts = {table:dest.execute(f'SELECT count(*) FROM {table}').fetchone()[0] for table in table_names}
        versions = dict(src.execute('SELECT key,value FROM metadata'))
        result = dict(package_format=FORMAT, schema_version=version, corpus_version=corpus_version,
                      normalization=versions['normalization'], unicode_version=versions['unicode_version'],
                      language_pairs=['en-ru','ru-en'], counts=counts, database_sha256=checksum(database),
                      sources=sorted({(a['source'],a['snapshot'],a['source_url']) for a in attributions.values()}),
                      attributions=attributions)
        manifest.write_text(json.dumps(result,ensure_ascii=True,sort_keys=True,indent=2)+'\n',encoding='utf-8')
        return validate_package(package)
    except BaseException:
        database.unlink(missing_ok=True)
        manifest.unlink(missing_ok=True)
        package.rmdir()
        raise


def schema_objects(conn):
    return sorted(conn.execute("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'"))


def package_schema(version):
    if type(version) is not int or version not in (1,2):
        raise ValueError('unsupported schema version')
    return (DISTRIBUTION,TABLES) if version == 1 else (DISTRIBUTION_V2,(*TABLES,'lexical_metadata'))


def validate_package(package):
    database = package/'dictionary.sqlite'
    manifest = package/'manifest.json'
    if manifest.stat().st_size > MAX_MANIFEST or database.stat().st_size > MAX_DATABASE:
        raise ValueError('dictionary package exceeds size limit')
    data = json.loads(manifest.read_text(encoding='utf-8'))
    if not isinstance(data,dict):
        raise ValueError('manifest must be an object')
    version = data.get('schema_version')
    schema, table_names = package_schema(version)
    if (data.get('package_format'),data.get('normalization'),
            data.get('language_pairs')) != (FORMAT,NORMALIZATION_VERSION,['en-ru','ru-en']):
        raise ValueError('unsupported dictionary package')
    for field in ('corpus_version','unicode_version'):
        if not isinstance(data.get(field),str) or not data[field].strip():
            raise ValueError('incomplete version metadata')
    if checksum(database) != data.get('database_sha256'):
        raise ValueError('database checksum mismatch')
    attrs = data.get('attributions')
    if not isinstance(attrs,dict) or not attrs:
        raise ValueError('missing attribution')
    for ref, record in attrs.items():
        if (not isinstance(record,dict) or set(record) != {*ATTR_FIELDS,'source','snapshot','locator'}
                or any(not isinstance(v,str) or not v.strip() for v in record.values())
                or len(record['snapshot']) != 64 or any(c not in '0123456789abcdef' for c in record['snapshot'])
                or not all(valid_url(record[k]) for k in ('source_url','terms_url'))
                or ref != hashlib.sha256(json.dumps(record,sort_keys=True).encode()).hexdigest()):
            raise ValueError('invalid attribution')
    sources = sorted({(r['source'],r['snapshot'],r['source_url']) for r in attrs.values()})
    if data.get('sources') != [list(row) for row in sources]:
        raise ValueError('inconsistent source list')
    with closing(sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True)) as conn, closing(sqlite3.connect(':memory:')) as expected:
        expected.executescript(schema)
        integrity(conn)
        if conn.execute('PRAGMA user_version').fetchone()[0] != version or schema_objects(conn) != schema_objects(expected):
            raise ValueError('wrong distribution schema or indexes')
        counts = {table:conn.execute(f'SELECT count(*) FROM {table}').fetchone()[0] for table in table_names}
        if counts != data.get('counts') or not all(counts[key] for key in ('entry','sense','translation')):
            raise ValueError('invalid package counts or empty corpus')
        if conn.execute('SELECT id FROM entry INTERSECT SELECT id FROM sense').fetchone():
            raise ValueError('duplicate persistent ID')
        for value, in conn.execute('SELECT id FROM entry UNION ALL SELECT id FROM sense'):
            if str(UUID(value)) != value:
                raise ValueError('invalid persistent ID')
        if conn.execute('SELECT 1 FROM entry e WHERE NOT EXISTS (SELECT 1 FROM sense s WHERE s.entry_id=e.id)').fetchone():
            raise ValueError('entry has no eligible sense')
        if conn.execute('SELECT 1 FROM sense s WHERE NOT EXISTS (SELECT 1 FROM translation t WHERE t.sense_id=s.id)').fetchone():
            raise ValueError('sense has no eligible translation')
        for table in table_names:
            if any(ref not in attrs for ref, in conn.execute(f'SELECT DISTINCT attribution_id FROM {table}')):
                raise ValueError('unresolved attribution')
        for origin, translation, sense, entry in conn.execute("""
                SELECT t.origin_attribution_id,t.attribution_id,s.attribution_id,e.attribution_id
                FROM translation t JOIN sense s ON s.id=t.sense_id JOIN entry e ON e.id=s.entry_id
        """):
            keys = (origin,translation,sense,entry)
            if origin not in attrs or len({(attrs[key]['source'],attrs[key]['snapshot']) for key in keys}) != 1:
                raise ValueError('inconsistent origin attribution')
    return data


def connect(path, mode):
    return closing(sqlite3.connect(path.resolve().as_uri()+'?mode='+mode, uri=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command',required=True)
    for name, arguments in (
        ('rights', ('staging','decisions')),
        ('bind', ('registry','decisions')),
        ('export', ('staging','registry','package','corpus_version')),
        ('validate', ('package',)),
        ('readiness', ('staging',)),
    ):
        command = commands.add_parser(name)
        if name == 'export':
            command.add_argument('--schema-version',type=int,choices=(1,2),default=1)
        if name == 'readiness':
            command.add_argument('--registry',type=Path)
        for arg in arguments:
            command.add_argument(arg, type=str if arg == 'corpus_version' else Path)
    args = parser.parse_args()
    data = Path(__file__).resolve().parents[2]/'data'
    for name, root in (('staging','processed'),('registry','processed'),('package','processed'),('decisions','review')):
        path = getattr(args,name,None)
        if path is not None and not path.resolve().is_relative_to((data/root).resolve()):
            parser.error(f'{name} must be under research/data/{root}/')
    if args.command == 'readiness':
        with connect(args.staging,'ro') as src:
            if args.registry is None:
                report = readiness(src)
            else:
                with connect(args.registry,'ro') as registry:
                    report = readiness(src,registry)
        print(json.dumps(report))
        return
    if args.command in ('rights','bind'):
        with args.decisions.open(encoding='utf-8') as stream:
            records = [json.loads(line) for line in stream]
        if args.command == 'bind':
            with connect(args.registry, 'rwc') as registry:
                registry.executescript(identity.SCHEMA)
                print(json.dumps({'bindings_added': identity.load_registry(registry,records)}))
        else:
            with connect(args.staging, 'rw') as staging:
                initialize(staging)
                # Each row is an explicit rights decision. Successful rows remain recorded on retry.
                for record in records:
                    record_rights(staging, **record)
                print(json.dumps({'rights_decisions_processed':len(records)}))
        return
    if args.command == 'export':
        with connect(args.staging,'ro') as staging, connect(args.registry,'ro') as registry:
            staging.execute('BEGIN')
            registry.execute('BEGIN')
            if registry.execute('SELECT version FROM identity_registry').fetchall() != [(1,)]:
                raise ValueError('unsupported identity registry')
            result = export_package(staging,registry,args.package,args.corpus_version,version=args.schema_version)
    else:
        result = validate_package(args.package)
    print(json.dumps({'counts':result['counts'],'database_sha256':result['database_sha256']}))


if __name__ == '__main__':
    main()
