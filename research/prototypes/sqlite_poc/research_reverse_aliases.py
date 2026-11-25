"""Measure indexed Russian search aliases without changing stored dictionary keys."""
import argparse
import json
import unicodedata
from pathlib import Path
from statistics import median
from time import perf_counter

from prototype import normalize, prefix_end
from release_package import connect
from research_package import DATA, validate_research_pair
from research_reverse import FOCUS
from research_evaluation import HOLDOUT

VOWELS = 'аеёиоуыэюя'


def stress_key(text):
    chars = []
    base = ''
    for char in unicodedata.normalize('NFD',normalize(text)):
        if unicodedata.combining(char)==0:
            base = char
        if char=='\u0301' and base and base in VOWELS:
            continue
        chars.append(char)
    return unicodedata.normalize('NFC',''.join(chars))


def scratch_index(db):
    db.executescript("""
        CREATE TEMP TABLE aliases(id INTEGER PRIMARY KEY,stress TEXT NOT NULL,yo TEXT NOT NULL);
        CREATE INDEX aliases_stress ON aliases(stress,id);
        CREATE INDEX aliases_yo ON aliases(yo,id);
    """)
    def keys():
        for identity,value in db.execute('SELECT id,search_key FROM translation'):
            key = stress_key(value)
            yield identity,key,key.replace('ё','е')
    db.executemany('INSERT INTO aliases VALUES (?,?,?)',keys())


def measure(db,queries):
    outcomes = []
    for word in queries:
        comparison = stress_key(word)
        phases = [
            ('literal','translation','search_key',normalize(word)),
            ('stress','aliases','stress',comparison),
            ('yo','aliases','yo',comparison.replace('ё','е')),
        ]
        count = {}
        for label,table,column,key in phases:
            count[label] = db.execute(
                f'SELECT count(*) FROM {table} WHERE {column}=?',(key,)).fetchone()[0]
        outcomes.append(dict(query=word,**count))
    collisions = db.execute("""
        SELECT count(*) FROM (SELECT yo FROM aliases GROUP BY yo HAVING count(DISTINCT stress)>1)
    """).fetchone()[0]
    timings = {}
    for phase,column,key in (('exact_stress','stress',stress_key('устойчивый')),
                             ('prefix_stress','stress',stress_key('устой')),
                             ('exact_yo','yo',stress_key('елка'))):
        sql = f'SELECT id FROM aliases WHERE {column}=? LIMIT 50'
        args = (key,)
        if phase.startswith('prefix'):
            sql = f'SELECT id FROM aliases WHERE {column}>=? AND {column}<? LIMIT 50'
            args = (key,prefix_end(key))
        plan = [row[3] for row in db.execute('EXPLAIN QUERY PLAN '+sql,args)]
        if not any('SEARCH' in node and 'INDEX' in node for node in plan):
            raise RuntimeError('alias query does not use an index')
        elapsed = []
        for _ in range(9):
            start = perf_counter()
            db.execute(sql,args).fetchall()
            elapsed.append((perf_counter()-start)*1000)
        timings[phase] = dict(n=9,median_ms=median(elapsed))
    return dict(total=len(outcomes),literal_hits=sum(row['literal']>0 for row in outcomes),
                stress_hits=sum(row['stress']>0 for row in outcomes),yo_hits=sum(row['yo']>0 for row in outcomes),
                collisions_after_yo_fold=collisions,windows_sqlite=timings,queries=outcomes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package',type=Path)
    args = parser.parse_args()
    if not args.package.resolve().is_relative_to((DATA/'processed').resolve()):
        parser.error('use a local research package')
    manifest = validate_research_pair(args.package)
    queries = tuple(dict.fromkeys((*FOCUS,*HOLDOUT['reverse'].split(','),'ёлка','елка','всё','все')))
    with connect(args.package/'dictionary.sqlite','ro') as db:
        scratch_index(db)
        report = measure(db,queries)
    print(json.dumps(dict(corpus=manifest['corpus_version'],**report),ensure_ascii=True,indent=2))


if __name__ == '__main__':
    main()
