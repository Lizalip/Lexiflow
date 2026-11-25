"""Measure exact and prefix queries against an actual local research pair."""
import argparse
import json
from collections import defaultdict
from pathlib import Path
from statistics import median
from time import perf_counter

from editor_workspace import diagnostic_alignment
from prototype import normalize, prefix_end
from release_package import connect, read_manifest
from research_package import CORE, DATA, screen, verified_release

QUERIES = {
    'basic': 'apple,book,house,water,friend,school,beautiful',
    'verbs': 'run,get,take,make,set,work,understand,learn',
    'polysemy': 'bank,light,mean,right,present',
    'advanced': 'resilient,awkward,empathy,ambiguous,sophisticated,compelling,curious',
    'academic': 'infer,constraint,assumption,evidence,hypothesis',
    'data_science': 'feature,bias,variance,inference,regression',
    'multiword_verbs': 'look after,give up,put off,figure out,carry on,turn down',
    'idioms_and_informal': 'break the ice,spill the beans,piece of cake,low-key,legit',
    'reverse': 'яблоко,книга,работать,понимать,устойчивый,исследование,признак',
}
KAIKKI_SNAPSHOT = '8cff3c5121393abf82909c5946217cc44fa9b06c640876705edb38d65dfe6894'


def search(db,key,reverse,exact):
    field = 't.search_key' if reverse else 'e.search_key'
    source = ('translation t JOIN sense s ON s.id=t.sense_id JOIN entry e ON e.id=s.entry_id'
              if reverse else 'entry e JOIN sense s ON s.entry_id=e.id')
    match = f'{field}=?' if exact else f'{field}>=? AND {field}<?'
    arguments = (key,) if exact else (key,prefix_end(key))
    return db.execute(f"""
        SELECT e.id,s.id,e.spelling,e.pos FROM {source} WHERE {match}
        GROUP BY e.id,s.id ORDER BY min({field})!=?,min({field}),e.id,s.position,s.id LIMIT 50
    """,(*arguments,key)).fetchall()


def metadata(db,rows,notes):
    entry_ids = {row[0] for row in rows}
    sense_ids = {row[1] for row in rows}
    if not entry_ids:
        return {}
    entries = ','.join('?' for _ in entry_ids)
    senses = ','.join('?' for _ in sense_ids)
    fields = dict(db.execute(f"""
        SELECT kind,count(*) FROM lexical_metadata
        WHERE entry_id IN ({entries}) OR sense_id IN ({senses}) GROUP BY kind
    """,(*entry_ids,*sense_ids)))
    fields['entry_definition_notes'] = sum(len(notes.get(entry, ())) for entry in entry_ids)
    for table,owner,identities,placeholders in (
        ('form','entry_id',entry_ids,entries),('example','sense_id',sense_ids,senses),
        ('translation','sense_id',sense_ids,senses)
    ):
        fields[table] = db.execute(f'SELECT count(*) FROM {table} WHERE {owner} IN ({placeholders})',tuple(identities)).fetchone()[0]
    return fields


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('staging',type=Path)
    parser.add_argument('package',type=Path)
    parser.add_argument('--kaikki',type=Path,help='optional pinned comparison staging, aggregates only')
    args = parser.parse_args()
    paths = (args.staging,args.package) + ((args.kaikki,) if args.kaikki else ())
    if not all(path.resolve().is_relative_to((DATA/'processed').resolve()) for path in paths):
        parser.error('use local research/data/processed paths')
    notes = read_manifest(args.package/'manifest.json').get('source_entry_notes', {}).get('entries', {})
    with connect(args.staging,'ro') as source, connect(args.package/'dictionary.sqlite','ro') as db:
        release = verified_release(source)
        rows = list(source.execute(CORE,(release,)*3))
        eligible,_ = screen(rows)
        source_english = {key for key, in source.execute("SELECT DISTINCT search_key FROM entry WHERE lang='en'")}
        source_russian = {normalize(row[8]) for row in rows}
        eligible_russian = {normalize(row[8]) for group in eligible.values() for row in group}
        report = []
        for group,words in QUERIES.items():
            reverse = group == 'reverse'
            for text in words.split(','):
                key = normalize(text)
                exists = key in (source_russian if reverse else source_english)
                kept = key in (eligible_russian if reverse else eligible)
                exact = search(db,key,reverse,True)
                hits = search(db,key,reverse,False)
                reason = None
                if not exact:
                    reason = ('absent from source exact keys' if not exists else
                              'outside research scope' if not kept else 'eligible but absent from selected subset')
                report.append(dict(group=group,query=text,exact_source=exists,exact_in_package=bool(exact),
                                   exact_senses_returned=len(exact),fields=metadata(db,exact,notes),
                                   prefix_senses_returned=len(hits),first_headwords=list(dict.fromkeys(r[2] for r in hits))[:5],
                                   reason=reason))
        metric_rows = defaultdict(list)
        for operation,text,reverse,exact in (('exact','book',False,True),
                ('prefix','bo',False,False),('reverse','книга',True,False)):
            for _ in range(7):
                start = perf_counter()
                if not search(db,normalize(text),reverse,exact):
                    raise ValueError(f'no hits for performance sample {operation}')
                metric_rows[operation].append((perf_counter()-start)*1000)
        result = dict(queries=report,windows_sqlite_ms={
            operation:dict(n=len(samples),median=median(samples),minimum=min(samples),maximum=max(samples))
            for operation,samples in metric_rows.items()})
        if args.kaikki:
            with connect(args.kaikki,'ro') as comparison:
                snapshots = set(comparison.execute('SELECT sha256 FROM release'))
                if snapshots != {(KAIKKI_SNAPSHOT,)}:
                    raise ValueError('comparison staging does not record the expected Kaikki snapshot')
                proposed,counts = diagnostic_alignment(source,comparison)
                active = {key for key, in db.execute('SELECT DISTINCT search_key FROM entry')}
                result['source_alignment'] = dict(counts=counts,selected=len(proposed),
                    selected_in_package=sum(normalize(row['spelling']) in active for row in proposed),imported=0)
        print(json.dumps(result,ensure_ascii=True,indent=2))


if __name__ == '__main__':
    main()
