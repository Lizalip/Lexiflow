"""Compute aggregate coverage independently of corpus selection. Export no source wording."""
import argparse
import csv
import json
from pathlib import Path

from prototype import normalize
from release_package import connect, read_manifest
from research_diagnostics import QUERIES
from research_evaluation import probe_set
from research_package import DATA
from research_reverse_aliases import stress_key


def probes(direct):
    return {
        'development55': [(category,word) for category,words in QUERIES.items() for word in words.split(',')],
        'heldout264': list(probe_set()),
        'direct128': [('reverse',row['query']) for row in direct],
    }


def coverage(package,sets):
    manifest = read_manifest(package/'manifest.json')
    cefr = manifest.get('entry_cefr',{}).get('entries',{})
    examples = manifest.get('entry_usage_examples',{}).get('entries',{})
    with connect(package/'dictionary.sqlite','ro') as db:
        english = db.execute("SELECT id,search_key FROM entry WHERE language='en'").fetchall()
        exact_en = {key for _,key in english}
        exact_ru = {key for key, in db.execute("SELECT search_key FROM entry WHERE language='ru'")}
        reverse = {key for key, in db.execute("SELECT DISTINCT search_key FROM translation WHERE language='ru'")}
        forms = {key for key, in db.execute('SELECT DISTINCT search_key FROM form')}
    accent_only = {stress_key(key) for key in reverse if key!=stress_key(key)}
    has_cefr = {key for owner,key in english if owner in cefr}
    has_example = {key for owner,key in english if owner in examples}
    for label,queries in sets.items():
        values = dict(exact=0,normalized=0,direct_ru=0,reverse_ru=0,forms=0,cefr=0,examples=0)
        for direction,text in queries:
            key = normalize(text)
            ru = direction=='reverse'
            direct = key in (exact_ru if ru else exact_en)
            inverse = ru and key in reverse
            form = not ru and key in forms
            exact = direct or inverse or form
            values['exact'] += exact
            values['normalized'] += exact or ru and key in accent_only
            values['direct_ru'] += ru and direct
            values['reverse_ru'] += inverse
            values['forms'] += form
            values['cefr'] += not ru and key in has_cefr
            values['examples'] += not ru and key in has_example
        yield dict(package=package.name,probe_set=label,queries=len(queries),**values,
                   cefr_headwords=len(has_cefr),cefr_entries=len(cefr),cefr_links=sum(map(len,cefr.values())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('direct',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('packages',type=Path,nargs='+')
    args = parser.parse_args()
    if (not args.direct.resolve().is_relative_to((DATA/'review').resolve())
            or not all(path.resolve().is_relative_to((DATA/'processed').resolve())
                       for path in [args.output,*args.packages])):
        parser.error('use local research inputs and a fresh processed output')
    sets = probes(json.loads(args.direct.read_text(encoding='utf-8')))
    if tuple(map(len,sets.values()))!=(55,264,128):
        raise ValueError('unexpected probe set sizes')
    rows = [row for package in args.packages for row in coverage(package,sets)]
    with args.output.open('x',encoding='utf-8',newline='') as stream:
        writer = csv.DictWriter(stream,fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)


if __name__=='__main__':
    main()
