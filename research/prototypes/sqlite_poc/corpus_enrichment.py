"""Prepare local FreeDict staging, aggregate coverage and a pending editorial pilot."""
import argparse
import csv
import json
import os
import sqlite3
from collections import Counter
from contextlib import closing
from pathlib import Path

from candidate_rank import subtlex_frequency
from diagnostics import diagnostic_spellings
from kaikki import SOURCE as KAIKKI_SOURCE
from prototype import create_database, integrity, normalize, snapshot_id
from release_package import STAGING_METADATA, checksum, connect, readiness
from review_queue import csv_cell, stratify, triage_rows
from tei_candidates import MAX_ARCHIVE, archive_tei

SOURCE = 'FreeDict / WikDict / DBnary / Wiktionary'
VERSION = '2025.11.23'
URL = f'https://download.freedict.org/dictionaries/eng-rus/{VERSION}/'
LICENSE = 'CC-BY-SA-3.0'
TERMS = 'https://creativecommons.org/licenses/by-sa/3.0/legalcode'
DATA = Path(__file__).resolve().parents[2]/'data'
STYLE = {'archaic','colloquial','formal','informal','obsolete','offensive','slang','taboo','vulgar'}
REGION = {'Australia','Canada','Ireland','New-Zealand','Scotland','UK','US','Wales'}
GRAMMAR = {'comparative','gerund','infinitive','participle','past','plural','present','singular',
           'superlative','third-person'}


def import_freedict(db, path):
    if path.stat().st_size > MAX_ARCHIVE:
        raise ValueError('archive exceeds 30 MiB')
    digest = checksum(path)
    header = None
    stats = Counter()

    def evidence(locator, external=None):
        return db.execute('INSERT INTO evidence(release_id,locator,external_id) VALUES (1,?,?)',
                          (locator,external)).lastrowid

    def metadata(parent, identifier, kind, item, lang='und'):
        db.execute(f"""INSERT INTO lexical_metadata({parent}_id,kind,value,lang,evidence_id)
                    VALUES (?,?,?,?,?)""", (identifier,kind,item['text'],lang,
                                             evidence(item['locator']+'/metadata/'+kind)))

    def sense_rows(senses, parent=None):
        for sense in senses:
            locator = sense['locator']
            sid = snapshot_id(SOURCE,digest,locator)
            yield sid, sense, parent
            yield from sense_rows(sense['senses'],sid)

    db.executescript(STAGING_METADATA)
    with db:
        db.execute('INSERT INTO source VALUES (1,?)',(SOURCE,))
        db.execute('INSERT INTO release VALUES (1,1,?,?)',(digest,VERSION))
        for kind, record in archive_tei(path):
            if kind == 'header':
                if header is not None or record.get('edition') != VERSION or TERMS not in record['links']:
                    raise ValueError('unexpected FreeDict edition or license declaration')
                header = record
                continue
            stats['source_entries'] += 1
            if header is None or record['language'] != 'en':
                raise ValueError('unexpected FreeDict document structure or entry language')
            headwords = [orth for form in record['forms'] if form['type'] not in ('inflected','inflection')
                         for orth in form['orthographies']]
            if not headwords:
                stats['entries_without_headword'] += 1
                continue
            word = headwords[0]['text']
            eid = snapshot_id(SOURCE,digest,record['locator'])
            pos = record['pos'][0]['text'] if len(record['pos']) == 1 else None
            db.execute('INSERT INTO entry VALUES (?,\'en\',?,?,?,?)',
                       (eid,word,normalize(word),pos,evidence(record['locator'],record['source_id'])))
            for form in record['forms']:
                for orth in form['orthographies']:
                    if orth is headwords[0]:
                        continue
                    fid = db.execute("""INSERT INTO form(entry_id,spelling,search_key,raw_metadata,evidence_id)
                                         VALUES (?,?,?,?,?)""",
                                     (eid,orth['text'],normalize(orth['text']),json.dumps(form['type']),
                                      evidence(orth['locator']))).lastrowid
                    for group in form['grammar']:
                        if group['value']:
                            metadata('form',fid,'grammar',dict(locator=group['locator'],text=group['value']))
                for item in form['pronunciations']:
                    value = item['text']
                    ipa = value if (item['attributes'].get('notation','').casefold() == 'ipa'
                                    or (len(value) > 2 and value.startswith('/') and value.endswith('/'))) else None
                    db.execute('INSERT INTO pronunciation(entry_id,ipa,raw_metadata,evidence_id) VALUES (?,?,?,?)',
                               (eid,ipa,json.dumps(item['attributes']),evidence(item['locator'])))
                    metadata('entry',eid,'ipa' if ipa else 'pronunciation',item)
                    for attr in ('region','variety'):
                        if item['attributes'].get(attr):
                            metadata('entry',eid,'region',dict(locator=item['locator']+'/'+attr,
                                                              text=item['attributes'][attr]))
            for position,(sid, sense, parent) in enumerate(sense_rows(record['senses']),1):
                tags = dict(parent_sense_id=parent, labels=sense['labels'])
                db.execute('INSERT INTO sense VALUES (?,?,?,?,?)',
                           (sid,eid,position,evidence(sense['locator'],sense['source_id']),json.dumps(tags)))
                for i, item in enumerate(sense['definitions'],1):
                    if item['language'] == 'en':
                        db.execute("""INSERT INTO gloss(sense_id,lang,body,role,position,evidence_id)
                                   VALUES (?,'en',?,'definition',?,?)""",
                                   (sid,item['text'],i,evidence(item['locator'])))
                        metadata('sense',sid,'definition',item,'en')
                for item in sense['labels']:
                    label = {'reg':'style','style':'style','geo':'region','region':'region'}.get(item['type'])
                    if label:
                        metadata('sense',sid,label,item)
                target_position = 0
                for citation in sense['citations']:
                    quotes = citation['quotes']
                    if citation['type'] == 'trans':
                        for item in quotes:
                            if item['language'] not in ('ru','rus'):
                                stats['non_russian_or_unlabelled_quotes'] += 1
                                continue
                            target_position += 1
                            # The existing release gate expects a reviewable origin row.
                            # It remains pending and distinct from an approved translation.
                            db.execute("""INSERT INTO gloss(sense_id,lang,body,role,position,evidence_id)
                                       VALUES (?,'ru',?,'source_gloss',?,?)""",
                                       (sid,item['text'],target_position,evidence(item['locator'])))
                    if citation['type'] == 'example':
                        context = '; '.join(item['text'] for item in citation['references']) or None
                        for item in quotes:
                            if item['language'] not in (None,'en','eng'):
                                continue
                            xid = db.execute("""INSERT INTO example(sense_id,lang,body,citation,evidence_id)
                                                 VALUES (?,'en',?,?,?)""",
                                             (sid,item['text'],context,evidence(item['locator']))).lastrowid
                            if len(quotes) == 1:
                                for nested in citation['nested_quotes']:
                                    if nested['language'] in ('ru','rus'):
                                        db.execute("""INSERT INTO example_translation(example_id,lang,body,evidence_id)
                                                       VALUES (?,'ru',?,?)""",
                                                   (xid,nested['text'],evidence(nested['locator'])))
        if header is None or not stats['source_entries'] or checksum(path) != digest:
            raise ValueError('missing source entries or source changed')
        manifest = dict(source=SOURCE, edition=VERSION, url=URL, license_id=LICENSE,
                        terms_url=TERMS, sha256=digest, archive_bytes=path.stat().st_size,
                        header=header, counts=dict(stats))
        db.execute("INSERT INTO metadata VALUES ('freedict_import',?)",(json.dumps(manifest),))
        integrity(db)
    return manifest


def coverage(db, *, structured=False):
    queries = {
        'entries': 'SELECT count(*) FROM entry',
        'normalized_spellings': 'SELECT count(DISTINCT search_key) FROM entry',
        'senses': 'SELECT count(*) FROM sense',
        'entries_without_pos': "SELECT count(*) FROM entry WHERE pos IS NULL OR pos=''",
        'multiword_spellings': "SELECT count(DISTINCT search_key) FROM entry WHERE instr(search_key,' ')>0",
        'spellings_with_ipa': """SELECT count(DISTINCT e.search_key) FROM entry e JOIN pronunciation p
                                  ON p.entry_id=e.id WHERE p.ipa IS NOT NULL""",
        'definitions': "SELECT count(*) FROM gloss WHERE role='definition'",
        'source_gloss_or_candidate_rows': "SELECT count(*) FROM gloss WHERE role='source_gloss'",
        'senses_without_gloss_or_candidate': """SELECT count(*) FROM sense s WHERE NOT EXISTS (
            SELECT 1 FROM gloss g WHERE g.sense_id=s.id AND g.role='source_gloss')""",
        'examples': 'SELECT count(*) FROM example',
        'translated_examples': 'SELECT count(*) FROM example_translation',
        'forms': 'SELECT count(*) FROM form',
        'senses_with_multiple_candidates': """SELECT count(*) FROM (SELECT sense_id FROM gloss
              WHERE role='source_gloss' GROUP BY sense_id HAVING count(DISTINCT body)>1)""",
        'spellings_with_multiple_pos': """SELECT count(*) FROM (SELECT search_key FROM entry
             GROUP BY search_key HAVING count(DISTINCT pos)>1)""",
    }
    result = {key:db.execute(sql).fetchone()[0] for key,sql in queries.items()}
    result['pos_entry_counts'] = dict(db.execute('SELECT pos,count(*) FROM entry GROUP BY pos'))
    targets = sorted({normalize(word) for word in diagnostic_spellings()})
    placeholders = ','.join('?' for _ in targets)
    result['diagnostic_spellings'] = len(targets)
    result['diagnostic_covered'] = db.execute(
        f'SELECT count(DISTINCT search_key) FROM entry WHERE search_key IN ({placeholders})',targets).fetchone()[0]
    result['structured_translation_candidates'] = result['source_gloss_or_candidate_rows'] if structured else 0
    if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='lexical_metadata'").fetchone():
        result['metadata'] = dict(db.execute('SELECT kind,count(*) FROM lexical_metadata GROUP BY kind'))
    result['release_readiness'] = readiness(db)
    return result


def prepare_kaikki_metadata(db):
    """Project explicit tags and values from existing staging into pending V2 metadata."""
    if [row[0] for row in db.execute('SELECT name FROM source')] != [KAIKKI_SOURCE]:
        raise ValueError('expected a single Kaikki source')
    db.executescript(STAGING_METADATA)
    stats = Counter()

    def add(parent, identifier, kind, value, lang, release, locator):
        evidence_id = db.execute('INSERT INTO evidence(release_id,locator) VALUES (?,?)',
                                 (release,locator)).lastrowid
        db.execute(f"""INSERT INTO lexical_metadata({parent}_id,kind,value,lang,evidence_id)
                    VALUES (?,?,?,?,?)""",(identifier,kind,value,lang,evidence_id))
        stats[kind] += 1

    with db:
        db.execute("INSERT INTO metadata VALUES ('kaikki_lexical_metadata','1')")
        for sid,lang,value,role,release,locator in db.execute("""
            SELECT g.sense_id,g.lang,g.body,g.role,ev.release_id,ev.locator
            FROM gloss g JOIN evidence ev ON ev.id=g.evidence_id ORDER BY g.id
        """):
            add('sense',sid,role,value,lang,release,locator+'/metadata/'+role)
        for table,parent,identifier,raw in (
                ('pronunciation','entry','entry_id','raw_metadata'),
                ('form','form','id','raw_metadata'),
                ('sense','sense','id','raw_tags')):
            ipa = 't.ipa' if table == 'pronunciation' else 'NULL'
            for ident,tags,value,release,locator in db.execute(f"""
                SELECT t.{identifier},t.{raw},{ipa},ev.release_id,ev.locator
                FROM {table} t JOIN evidence ev ON ev.id=t.evidence_id ORDER BY ev.id
            """):
                tags = json.loads(tags)
                if not isinstance(tags,dict):
                    continue
                if value:
                    add(parent,ident,'ipa',value,'und',release,locator+'/ipa')
                raw_tags = tags.get('tags')
                if not isinstance(raw_tags,list):
                    continue
                for i, tag in enumerate(raw_tags):
                    if not isinstance(tag,str):
                        continue
                    kind = None
                    if table == 'pronunciation' and value and tag in ('singular','plural'):
                        kind = 'pronunciation'
                    elif table == 'form' and tag in GRAMMAR:
                        kind = 'grammar'
                    elif table == 'sense' and tag in STYLE:
                        kind = 'style'
                    elif table != 'form' and tag in REGION:
                        kind = 'region'
                    if kind:
                        label = f'{value} ({tag})' if kind == 'pronunciation' else tag
                        add(parent,ident,kind,label,'und',release,f'{locator}/tags/{i}/metadata')
        integrity(db)
    return dict(stats)


def pilot_rows(db, frequency, limit):
    if not 100 <= limit <= 300:
        raise ValueError('pilot must target 100–300 spellings')
    db.row_factory = sqlite3.Row
    candidates = {}
    for row in triage_rows(db, 'standard'):
        if row['source'] != SOURCE or not row['pos'] or not row['definition_context']:
            continue
        key = normalize(row['headword'])
        score = (-frequency.get(key,0),row['locator'])
        if key not in candidates or score < candidates[key][0]:
            candidates[key] = score,row
    return [row for _,row in sorted(candidates.values(),key=lambda item:item[0])[:limit]]


def export_pilot(db, path, qa_path, frequency, limit):
    rows = pilot_rows(db,frequency,limit)
    fields = ('headword','pos','sense_id','proposed_translation','definition_context','gloss',
              'entry_id','snapshot_sha256','locator','source','source_url','license_id','terms_url',
              'reason','review_priority','observed_lowercase_count')
    with path.open('x',encoding='utf-8-sig',newline='') as stream:
        writer = csv.DictWriter(stream,fieldnames=fields)
        writer.writeheader()
        for row in rows:
            row.update(source_url=URL,license_id=LICENSE,terms_url=TERMS,
                       observed_lowercase_count=frequency.get(normalize(row['headword'])))
            writer.writerow({key:csv_cell(row[key]) for key in fields})
    db.row_factory = sqlite3.Row
    selected = {normalize(row['headword']) for row in rows}
    with qa_path.open('x',encoding='utf-8',newline='\n') as stream:
        remaining = (row for row in triage_rows(db) if normalize(row['headword']) not in selected)
        qa = list(stratify(remaining,96,1729))
        for row in qa:
            stream.write(json.dumps(row,ensure_ascii=False)+'\n')
    return dict(pilot_spellings=len(rows), independent_qa_rows=len(qa),
                pilot_observed=sum(bool(row['observed_lowercase_count']) for row in rows))


def export_ambiguities(db, path):
    """Report distinct candidate strings on the same source sense for human judgment."""
    rows = db.execute("""
        SELECT e.spelling,e.pos,s.id,r.sha256,ev.locator,g.body
        FROM gloss g JOIN sense s ON s.id=g.sense_id JOIN entry e ON e.id=s.entry_id
        JOIN evidence ev ON ev.id=g.evidence_id JOIN release r ON r.id=ev.release_id
        WHERE g.role='source_gloss' AND s.id IN (
           SELECT sense_id FROM gloss WHERE role='source_gloss'
           GROUP BY sense_id HAVING count(DISTINCT body)>1)
        ORDER BY s.id,g.position
    """)
    count = 0
    previous = None
    with path.open('x',encoding='utf-8-sig',newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(('headword','pos','sense_id','snapshot_sha256','locator','candidate','signal'))
        for row in rows:
            if row[2] != previous:
                count += 1
                previous = row[2]
            writer.writerow([*(csv_cell(value) for value in row),'multiple source candidates; review equivalence'])
    return {'senses_with_competing_source_candidates':count}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command',required=True)
    ingest = sub.add_parser('import-freedict')
    ingest.add_argument('archive',type=Path)
    ingest.add_argument('staging',type=Path)
    measure = sub.add_parser('coverage')
    measure.add_argument('staging',type=Path)
    pilot = sub.add_parser('pilot')
    pilot.add_argument('staging',type=Path)
    pilot.add_argument('frequency',type=Path)
    pilot.add_argument('csv',type=Path)
    pilot.add_argument('qa',type=Path)
    pilot.add_argument('--limit',type=int,default=200)
    conflicts = sub.add_parser('ambiguities')
    conflicts.add_argument('staging',type=Path)
    conflicts.add_argument('csv',type=Path)
    metadata = sub.add_parser('kaikki-metadata')
    metadata.add_argument('staging',type=Path)
    metadata.add_argument('output',type=Path)
    args = parser.parse_args()
    if not args.staging.resolve().is_relative_to((DATA/'processed').resolve()):
        parser.error('staging must be under research/data/processed/')
    if args.command == 'import-freedict':
        fd = os.open(args.staging,os.O_CREAT | os.O_EXCL | os.O_WRONLY,0o600)
        os.close(fd)
        try:
            with closing(sqlite3.connect(args.staging)) as db:
                create_database(db)
                result = import_freedict(db,args.archive)
        except BaseException:
            args.staging.unlink(missing_ok=True)
            raise
    elif args.command == 'kaikki-metadata':
        if not args.output.resolve().is_relative_to((DATA/'processed').resolve()):
            parser.error('output must be under research/data/processed/')
        args.output.touch(exist_ok=False)
        try:
            with connect(args.staging,'ro') as source, closing(sqlite3.connect(args.output)) as db:
                source.backup(db)
                db.execute('PRAGMA foreign_keys=ON')
                result = prepare_kaikki_metadata(db)
        except BaseException:
            args.output.unlink(missing_ok=True)
            raise
    else:
        with connect(args.staging,'ro') as db:
            structured = db.execute("SELECT 1 FROM metadata WHERE key='freedict_import'").fetchone() is not None
            if args.command == 'coverage':
                result = coverage(db,structured=structured)
            else:
                if not structured:
                    parser.error('editorial export requires FreeDict staging')
                outputs = (args.csv,args.qa) if args.command == 'pilot' else (args.csv,)
                if any(not output.resolve().is_relative_to((DATA/'review').resolve()) for output in outputs):
                    parser.error('output files must be under research/data/review/')
                result = (export_pilot(db,args.csv,args.qa,subtlex_frequency(args.frequency),args.limit)
                          if args.command == 'pilot' else export_ambiguities(db,args.csv))
    print(json.dumps(result,indent=2))


if __name__ == '__main__':
    main()
