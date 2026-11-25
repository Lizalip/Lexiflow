"""Desktop SQLite measurements on artificial dictionary and personal-store fixtures."""
import argparse
import json
import os
import sqlite3
import tracemalloc
from contextlib import closing
from pathlib import Path
from time import perf_counter

from pilot_measure import time_lookup
from prototype import integrity, prefix_end
from release_package import COMPACT_FORMAT, FORMAT, connect, expand_attributions, validate_package

ROOM_SCHEMA = Path(__file__).resolve().parents[3]/'app/schemas/app.lexiflow.data.PersonalDatabase/1.json'
PERSONAL_QUERIES = {
    'saved': ('SELECT * FROM saved ORDER BY id DESC', ()),
    'collections': ('SELECT * FROM collections ORDER BY name,id', ()),
    'memberships': ('SELECT * FROM members ORDER BY collectionId,savedId', ()),
    'collection_25': ('SELECT savedId FROM members WHERE collectionId=? ORDER BY savedId', (25,)),
}


def timed_query(db, sql, params):
    result = time_lookup(lambda: db.execute(sql,params).fetchall())
    result['plan'] = [row[3] for row in db.execute('EXPLAIN QUERY PLAN '+sql,params)]
    return result


def personal_report(output):
    output.touch(exist_ok=False)
    with closing(sqlite3.connect(output)) as db:
        db.execute('PRAGMA foreign_keys=ON')
        entities = json.loads(ROOM_SCHEMA.read_text(encoding='utf-8'))['database']['entities']
        for entity in entities:
            name = entity['tableName']
            db.execute(entity['createSql'].replace('${TABLE_NAME}',name))
            for index in entity.get('indices',[]):
                db.execute(index['createSql'].replace('${TABLE_NAME}',name))
        start = perf_counter()
        with db:
            db.executemany('INSERT INTO saved VALUES (?,?,?,?,?,?,?)',
                           ((i,f'e{i}',f's{i}',f'fixture-{i}','artificial','','') for i in range(1,10001)))
            db.executemany('INSERT INTO collections VALUES (?,?)',
                           ((i,f'collection-{i:03}') for i in range(1,101)))
            db.executemany('INSERT INTO members VALUES (?,?)',
                           (((i-1)%100+1,i) for i in range(1,10001)))
        elapsed = perf_counter()-start
        integrity(db)
        return dict(sqlite=sqlite3.sqlite_version, database_bytes=output.stat().st_size,
                    insert_seconds=round(elapsed,3),
                    queries={key:timed_query(db,*query) for key,query in PERSONAL_QUERIES.items()})


def flat_manifest_sizes(data):
    restored = dict(data,package_format=FORMAT)
    restored['attributions'] = expand_attributions(restored.pop('policies'),restored['attributions'])

    def size(newline, **options):
        encoder = json.JSONEncoder(ensure_ascii=True,sort_keys=True,**options)
        extra = len(newline)-1
        return len(newline) + sum(len(chunk)+extra*chunk.count('\n')
                                  for chunk in encoder.iterencode(restored))

    return dict(formatted_bytes=size(os.linesep,indent=2),
                minified_bytes=size('\n',separators=(',',':')))


def dictionary_report(package, trace_memory, flat_sizes=False):
    start = perf_counter()
    data = validate_package(package)
    report = dict(package_format=data['package_format'], counts=data['counts'],
                  validation_seconds=round(perf_counter()-start,3),
                  manifest_bytes=(package/'manifest.json').stat().st_size,
                  database_bytes=(package/'dictionary.sqlite').stat().st_size)
    if trace_memory:
        tracemalloc.start()
        try:
            validate_package(package)
            report['validation_peak_python_allocated_bytes'] = tracemalloc.get_traced_memory()[1]
        finally:
            tracemalloc.stop()
    with connect(package/'dictionary.sqlite','ro') as db:
        key = 'artificial-'
        args = key,prefix_end(key)
        forward = """
            WITH hits AS (
              SELECT s.id AS sid,e.search_key FROM entry e JOIN sense s ON s.entry_id=e.id
              WHERE e.search_key>=? AND e.search_key<?
              UNION ALL
              SELECT s.id,f.search_key FROM form f JOIN sense s ON s.entry_id=f.entry_id
              WHERE f.search_key>=? AND f.search_key<?
            ), grouped AS (SELECT sid,min(search_key) AS best FROM hits GROUP BY sid)
            SELECT sid,best FROM grouped ORDER BY (best=?) DESC,best,sid LIMIT 50
        """
        reverse = """SELECT sense_id,min(search_key) AS best FROM translation
                     WHERE search_key>=? AND search_key<? GROUP BY sense_id
                     ORDER BY (best=?) DESC,best,sense_id LIMIT 50"""
        report['broad_forward'] = timed_query(db,forward,(*args,*args,key))
        report['broad_reverse'] = timed_query(db,reverse,(*args,key))
    if flat_sizes:
        if data['package_format'] != COMPACT_FORMAT:
            raise ValueError('flat sizes require a compact manifest')
        report['reconstructed_flat_manifest'] = flat_manifest_sizes(data)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('dictionary','personal'))
    parser.add_argument('path',type=Path)
    parser.add_argument('--trace-memory',action='store_true')
    parser.add_argument('--flat-sizes',action='store_true')
    args = parser.parse_args()
    local_root = Path(__file__).resolve().parents[2]/'data'/'processed'
    if not args.path.resolve().is_relative_to(local_root.resolve()):
        parser.error('path must be under research/data/processed/')
    result = (personal_report(args.path) if args.mode == 'personal'
              else dictionary_report(args.path,args.trace_memory,args.flat_sizes))
    print(json.dumps(result,indent=2))
