"""Measure morphology against fixed lexical probes chosen independently of package contents."""
import argparse
import csv
import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path

from release_package import connect
from research_diagnostics import KAIKKI_SNAPSHOT
from research_package import DATA

# These probes supplement the earlier irregular-form smoke set. The meanings and
# tags below specify one valid reading per query, not an exhaustive answer list.
PROBES = (
    ('jumped','jump','v','Past'),('swam','swim','v','Past'),
    ('drove','drive','v','Past'),('broke','break','v','Past'),
    ('grown','grow','v','Past participle'),('driven','drive','v','Past participle'),
    ('spoken','speak','v','Past participle'),('forgotten','forget','v','Past participle'),
    ('singing','sing','v','Present participle'),('dancing','dance','v','Present participle'),
    ('cooking','cook','v','Present participle'),('studying','study','v','Present participle'),
    ('eats','eat','v','Third-person singular'),('tries','try','v','Third-person singular'),
    ('walks','walk','v','Third-person singular'),('speaks','speak','v','Third-person singular'),
    ('geese','goose','n','Plural'),('feet','foot','n','Plural'),
    ('teeth','tooth','n','Plural'),('boxes','box','n','Plural'),
    ('faster','fast','adj','Comparative'),('heavier','heavy','adj','Comparative'),
    ('longest','long','adj','Superlative'),('largest','large','adj','Superlative'),
    ('qzttzz','',None,None),('writed','',None,None),
)

SOURCE_FEATURES = {
    ('noun',('plural',)): ('n','Plural'),
    ('verb',('gerund',)): ('v','Gerund'),
    ('verb',('past',)): ('v','Past'),
    ('verb',('participle','past')): ('v','Past participle'),
    ('verb',('singular','third-person')): ('v','Third-person singular'),
}


def independent_probes(source, quota=30):
    if set(source.execute('SELECT sha256 FROM release'))!={(KAIKKI_SNAPSHOT,)}:
        raise ValueError('expected the pinned local Kaikki staging snapshot')
    groups = defaultdict(dict)
    for lemma,pos,surface,raw,locator in source.execute("""
        SELECT e.search_key,e.pos,f.search_key,f.raw_metadata,v.locator
        FROM form f JOIN entry e ON e.id=f.entry_id JOIN evidence v ON v.id=f.evidence_id
        WHERE e.lang='en' AND v.rights<>'excluded' ORDER BY v.locator
    """):
        tags = json.loads(raw).get('tags') or []
        category = SOURCE_FEATURES.get((pos,tuple(sorted(tags))))
        if (category is None or lemma==surface or not all(re.fullmatch('[a-z]{1,40}',word)
                for word in (lemma,surface)) or not locator):
            continue
        part,feature = category
        probe = surface,lemma,part,feature
        if probe not in PROBES:
            groups[feature].setdefault(probe,locator)
    result = {}
    for feature,members in sorted(groups.items()):
        ranked = sorted(members, key=lambda probe: hashlib.sha256(
            ('lexiflow-morph-qa-v1/'+'/'.join(probe)).encode()).digest())
        for probe in ranked[:quota]:
            result[probe] = members[probe]
    return result


def evaluate(package,probes=PROBES):
    with connect(package/'dictionary.sqlite','ro') as db:
        for query,lemma,pos,feature in probes:
            exact = db.execute("SELECT count(*) FROM entry WHERE language='en' AND search_key=?",(query,)).fetchone()[0]
            analyses = set(db.execute("""
                SELECT e.search_key,e.pos,m.value FROM form f JOIN entry e ON e.id=f.entry_id
                JOIN lexical_metadata m ON m.form_id=f.id
                WHERE f.search_key=? AND e.language='en' AND m.kind='grammar'
            """,(query,)))
            yield dict(package=package.name,query=query,expected_lemma=lemma,expected_pos=pos,
                       expected_feature=feature,expected_found=(lemma,pos,feature) in analyses if lemma else not analyses and not exact,
                       exact_headwords=exact,analyses=len(analyses))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    parser.add_argument('packages',type=Path,nargs='+')
    parser.add_argument('--cross-source',type=Path,help='compare an independent local Kaikki staging sample')
    args = parser.parse_args()
    if not all(p.resolve().is_relative_to((DATA/'processed').resolve()) for p in [args.output,*args.packages]):
        parser.error('use local processed packages and a fresh processed output')
    if args.cross_source:
        if not args.cross_source.resolve().is_relative_to((DATA/'processed').resolve()):
            parser.error('use local staging under research/data/processed')
        with connect(args.cross_source,'ro') as source:
            probes = independent_probes(source)
        if len(probes)!=150:
            parser.error('insufficient pinned cross-source probes')
        rows = [dict(row,diagnostic_source=KAIKKI_SNAPSHOT,source_locator=locator)
                for package in args.packages for row,locator in zip(evaluate(package,probes),probes.values())]
    else:
        rows = [row for package in args.packages for row in evaluate(package)]
    with args.output.open('x',encoding='utf-8',newline='') as stream:
        writer = csv.DictWriter(stream,fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)


if __name__ == '__main__':
    main()
