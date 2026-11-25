"""Generate a scalable artificial V2 package with the ordinary release exporter."""
import argparse
import hashlib
import json
import sqlite3
from contextlib import closing
from pathlib import Path
from time import perf_counter
from uuid import UUID

import identity
from prototype import create_database
from release_package import export_package
from review_queue import initialize


def make(output,count):
    if type(count) is not int or not 1 <= count <= 20_000:
        raise ValueError('synthetic scale must be 1..20,000')
    sha = hashlib.sha256(f'lexiflow-scale-v1/{count}'.encode()).hexdigest()
    with closing(sqlite3.connect(':memory:')) as src, closing(sqlite3.connect(':memory:')) as registry:
        create_database(src)
        initialize(src)
        registry.executescript(identity.SCHEMA)
        with src, registry:
            src.execute("INSERT INTO source VALUES (1,'LexiFlow artificial scale fixture')")
            src.execute("INSERT INTO release VALUES (1,1,?,'artificial-scale-v1')",(sha,))
            for i in range(1,count+1):
                word = f'artificial-scale-{i:06}'
                answer = f'artificial-answer-{i:06}'
                entry,sense = f'e{i}',f's{i}'
                ee,se,ge,te = range(i*4-3,i*4+1)
                rows = [(eid,f'artificial/{i}/{kind}') for eid,kind in
                        ((ee,'entry'),(se,'sense'),(ge,'gloss'),(te,'translation'))]
                src.executemany("""
                    INSERT INTO evidence(id,release_id,locator,rights,review_note)
                    VALUES (?,1,?,'cleared','artificial fixture only')
                """,rows)
                src.executemany("""
                    INSERT INTO rights_decision VALUES (?,'https://example.invalid/lexiflow-fixture',
                        'synthetic','https://example.invalid/lexiflow-fixture','LexiFlow artificial scale fixture',
                        'generated','cleared','artificial fixture only','fixture')
                """,[(eid,) for eid,_ in rows])
                src.execute('INSERT INTO entry VALUES (?,\'en\',?,?,\'noun\',?)',(entry,word,word,ee))
                src.execute('INSERT INTO sense VALUES (?,?,1,?,\'[]\')',(sense,entry,se))
                src.execute("INSERT INTO gloss VALUES (?,?,'ru',?,'source_gloss',1,?)",(i,sense,answer,ge))
                src.execute("INSERT INTO translation VALUES (?,?,'ru',?,?,?)",(i,sense,answer,answer,te))
                src.execute("INSERT INTO semantic_decision VALUES (?,?,'candidate_translation','fixture','human','artificial fixture only',?)",
                            (f'artificial/{i}',i,i))
                registry.executemany('INSERT INTO identity_binding VALUES (?,?,?,?,?,NULL,NULL)',[
                    ('entry',sha,entry,str(UUID(int=i*2-1)),'artificial only'),
                    ('sense',sha,sense,str(UUID(int=i*2)),'artificial only')])
        return export_package(src,registry,output,f'synthetic-scale-{count}',version=2)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    parser.add_argument('--entries',type=int,default=5000)
    args = parser.parse_args()
    local_root = Path(__file__).resolve().parents[2]/'data'/'processed'
    if not args.output.resolve().is_relative_to(local_root.resolve()):
        parser.error('output must be under research/data/processed/')
    start = perf_counter()
    result = make(args.output,args.entries)
    print(json.dumps(dict(counts=result['counts'], manifest_bytes=(args.output/'manifest.json').stat().st_size,
                          database_bytes=(args.output/'dictionary.sqlite').stat().st_size,
                          build_seconds=round(perf_counter()-start,3))))
