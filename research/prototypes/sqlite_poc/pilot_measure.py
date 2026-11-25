"""Aggregate desktop measurements for an ignored staging database or synthetic package."""
import argparse
import json
import sqlite3
import statistics
from contextlib import closing
from pathlib import Path
from time import perf_counter_ns

from prototype import integrity, query_plan, research_lookup
from review_queue import progress


def milliseconds(samples):
    ordered = sorted(samples)
    return dict(median=round(statistics.median(ordered),3),
                p95=round(ordered[(95*len(ordered)-1)//100],3))


def time_lookup(operation, repeats=30):
    times = []
    keys = []
    for _ in range(repeats):
        start = perf_counter_ns()
        keys = operation()
        times.append((perf_counter_ns()-start)/1e6)
    return dict(first=round(times[0],3), hits=len(keys), **milliseconds(times[1:]))


def staging_report(path):
    with closing(sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True)) as db:
        integrity(db)
        first = db.execute('SELECT search_key FROM entry ORDER BY search_key,id LIMIT 1').fetchone()
        if first is None:
            raise ValueError('empty staging')
        key = db.execute("""SELECT substr(search_key,1,1) AS prefix FROM entry
                         GROUP BY prefix ORDER BY count(*) DESC,prefix LIMIT 1""").fetchone()[0]
        result = dict(database_bytes=path.stat().st_size,sqlite=sqlite3.sqlite_version,
                      integrity='ok',foreign_key_violations=0,review=progress(db),
                      exact=time_lookup(lambda:research_lookup(db,'en-ru',first[0],exact=True,limit=50)),
                      broad=time_lookup(lambda:research_lookup(db,'en-ru',key,limit=50)),
                      candidate_plan=query_plan(db,'en-ru',key))
        result['broad']['entries_before_grouping'] = db.execute(
            'SELECT count(*) FROM entry WHERE substr(search_key,1,1)=?',(key,)).fetchone()[0]
        result['sense_rows'] = db.execute('SELECT count(*) FROM sense').fetchone()[0]
        result['pending_or_excluded_translations'] = db.execute("""
            SELECT count(*) FROM translation t JOIN evidence ev ON ev.id=t.evidence_id
            WHERE ev.rights!='cleared'
        """).fetchone()[0]
        result['translation_rows'] = db.execute('SELECT count(*) FROM translation').fetchone()[0]
        return result


def manifest_report(package):
    path = package/'manifest.json'
    with path.open(encoding='utf-8') as stream:
        manifest = json.load(stream)
    policies = {}
    links = {}
    for ref,record in manifest['attributions'].items():
        policy = json.dumps({k:v for k,v in record.items() if k not in ('locator','modification')},
                            ensure_ascii=True,sort_keys=True,separators=(',',':'))
        policy_id = policies.setdefault(policy,len(policies))
        links[ref] = [policy_id,record['locator'],record['modification']]
    compact = {**manifest,'attributions':links,'policies':[json.loads(policy) for policy in policies]}
    return dict(actual_bytes=path.stat().st_size,rows=len(links),distinct_policy_candidates=len(policies),
                illustrative_compact_bytes=len(json.dumps(compact,ensure_ascii=True,separators=(',',':')).encode()))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('staging','manifest'))
    parser.add_argument('path',type=Path)
    args = parser.parse_args()
    report = staging_report if args.mode == 'staging' else manifest_report
    print(json.dumps(report(args.path),indent=2))
