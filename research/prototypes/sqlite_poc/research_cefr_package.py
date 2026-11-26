"""Add source-attributed CEFR-J levels to English lemma/POS pairs in a research manifest."""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path
from shutil import copyfile
from tempfile import TemporaryDirectory

from release_package import CEFR_LICENSE, CEFR_SHA, CEFR_SOURCE, CEFR_URL, cefr_modification, checksum, connect
from research_cefr import POS, workbook_rows
from research_package import DATA, validate_research_pair


def add_levels(manifest,db,rows):
    if manifest['package_format']!=3 or 'entry_cefr' in manifest:
        raise ValueError('expected a research format 3 package without CEFR')
    manifest['corpus_version'] += '-cefr-j'
    policy = dict(source=CEFR_SOURCE,snapshot=CEFR_SHA,source_url=CEFR_URL,license_id=CEFR_LICENSE,
                  terms_url=CEFR_URL,attribution='The CEFR-J Wordlist Version 1.6 · Yukio Tono, Tokyo University of Foreign Studies · Retrieved 2026-11-25')
    policy_index = len(manifest['policies'])
    modifications = {text:index for index,text in enumerate(manifest['modifications'])}
    candidates = defaultdict(list)
    for line,key,raw_pos,level in rows:
        if raw_pos in POS:
            candidates[(key,POS[raw_pos])].append((line,level))
    labels = {}
    clause = " WHERE language='en'" if manifest['schema_version']==3 else ''
    for entry,key,pos in db.execute('SELECT id,search_key,pos FROM entry'+clause):
        for line,level in candidates.get((key,pos),()):
            modification = cefr_modification(key,pos)
            if modification not in modifications:
                modifications[modification] = len(manifest['modifications'])
                manifest['modifications'].append(modification)
            locator = f'ALL_sep/row/{line}/{level}'
            canonical = dict(policy,locator=locator,modification=modification)
            ref = hashlib.sha256(json.dumps(canonical,sort_keys=True).encode()).hexdigest()
            manifest['attributions'][ref] = [policy_index,locator,modifications[modification]]
            labels.setdefault(entry,[]).append(dict(level=level,evidence=ref))
    if not labels:
        raise ValueError('no known CEFR entries')
    manifest['policies'].append(policy)
    manifest['sources'] = sorted([*manifest['sources'],[CEFR_SOURCE,CEFR_SHA,CEFR_URL]])
    manifest['entry_cefr'] = dict(version=1,entries=labels)
    return dict(articles=len(labels),links=sum(map(len,labels.values())),
                articles_with_multiple_levels=sum(len({row['level'] for row in value})>1 for value in labels.values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base',type=Path)
    parser.add_argument('archive',type=Path)
    parser.add_argument('output',type=Path)
    args = parser.parse_args()
    if not all(path.resolve().is_relative_to((DATA/'processed').resolve()) for path in (args.base,args.output)):
        parser.error('expected processed local paths')
    if args.output.exists():
        parser.error('output already exists')
    manifest = validate_research_pair(args.base)
    with connect(args.base/'dictionary.sqlite','ro') as db:
        stats = add_levels(manifest,db,workbook_rows(args.archive))
    with TemporaryDirectory(dir=args.output.parent) as temporary:
        candidate = Path(temporary)/'candidate'
        candidate.mkdir()
        copyfile(args.base/'dictionary.sqlite',candidate/'dictionary.sqlite')
        with (candidate/'manifest.json').open('w',encoding='utf-8',newline='\n') as stream:
            json.dump(manifest,stream,ensure_ascii=True,sort_keys=True,separators=(',',':'))
            stream.write('\n')
        validate_research_pair(candidate)
        candidate.rename(args.output)
    print(json.dumps(dict(stats,manifest_sha256=checksum(args.output/'manifest.json')),indent=2))


if __name__=='__main__':
    main()
