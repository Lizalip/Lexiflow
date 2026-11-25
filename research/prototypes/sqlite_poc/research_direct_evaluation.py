"""Draw a disjoint direct RU–EN sample from keys missing in the baseline app."""
import argparse
import hashlib
import json
from bisect import bisect_left
from collections import Counter, defaultdict
from pathlib import Path

from prototype import normalize
from release_package import connect
from research_diagnostics import QUERIES
from research_evaluation import HOLDOUT
from research_package import DATA, validate_research_pair
from research_reverse import direct_candidates, verified_direct
from research_reverse_aliases import SINGLE_RUSSIAN_WORD, stress_key

SEED = 'lexiflow-direct-2026-11-26-v1'


def sample(baseline,direct):
    prior = {normalize(query) for group in (QUERIES,HOLDOUT)
             for text in group.values() for query in text.split(',')}
    literal = sorted({key for key, in baseline.execute('SELECT DISTINCT search_key FROM translation')})
    unstressed = {stress_key(key) for key in literal}
    strata = defaultdict(set)
    for key,pos in direct.execute("""
            SELECT DISTINCT e.search_key,e.pos FROM entry e JOIN sense s ON s.entry_id=e.id
            JOIN gloss g ON g.sense_id=s.id
            WHERE e.lang='ru' AND g.lang='en' AND g.role='source_gloss'"""):
        if key in prior or key in unstressed or not SINGLE_RUSSIAN_WORD.fullmatch(key):
            continue
        i = bisect_left(literal,key)
        if i<len(literal) and literal[i].startswith(key):
            continue
        strata['shorter' if len(key)<=8 else 'longer'].add(key)
    drawn = [key for name,keys in sorted(strata.items()) for key in sorted(keys,
             key=lambda key:hashlib.sha256(f'{SEED}/{name}/{key}'.encode()).digest())[:64]]
    rows = [dict(query=key,stratum='shorter' if len(key)<=8 else 'longer',
                 baseline='missing',direct=direct_candidates(direct,key)) for key in drawn]
    return rows,dict(seed=SEED,frame={k:len(v) for k,v in strata.items()},sample=dict(Counter(
        row['stratum'] for row in rows)),baseline_literal=0,baseline_prefix=0,baseline_stress=0,
        direct_source_matches=sum(bool(row['direct']) for row in rows),reviewed=0)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package',type=Path)
    parser.add_argument('direct',type=Path)
    parser.add_argument('output',type=Path)
    args=parser.parse_args()
    if (not all(p.resolve().is_relative_to((DATA/'processed').resolve()) for p in (args.package,args.direct))
            or not args.output.resolve().is_relative_to((DATA/'review').resolve())):
        parser.error('use local processed inputs and a new local review output')
    validate_research_pair(args.package)
    with connect(args.package/'dictionary.sqlite','ro') as base, connect(args.direct,'ro') as direct:
        verified_direct(direct)
        rows,report=sample(base,direct)
    with args.output.open('x',encoding='utf-8') as stream:
        json.dump(rows,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,indent=2))
