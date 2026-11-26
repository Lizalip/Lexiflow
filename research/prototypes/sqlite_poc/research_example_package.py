"""Export one verified Tatoeba pair as an article-level Research usage example."""
import argparse
import hashlib
import json
from pathlib import Path
from shutil import copyfile
from tempfile import TemporaryDirectory

from prototype import normalize
from release_package import (USAGE_CAPTURES, USAGE_EXTENSION, USAGE_LICENSES, USAGE_SNAPSHOT, USAGE_SOURCE, USAGE_URL,
                             checksum, connect, usage_modification, usage_relationship, usage_token_matches)
from research_package import DATA, validate_research_pair
from research_tatoeba import pairs, token_phrase, WORD


def add_example(manifest, db, capture, original_id, translation_id):
    if manifest['schema_version'] != 3 or USAGE_EXTENSION in manifest:
        raise ValueError('expected an unmodified bilingual research package')
    matches = [(original, translation) for original, translation in pairs(capture)
               if (original['id'],translation['id']) == (original_id,translation_id)]
    if len(matches) != 1:
        raise ValueError('expected one direct pair in the pinned capture')
    owner = db.execute("SELECT id FROM entry WHERE language='en' AND search_key='chance' AND pos='n'").fetchall()
    original, translation = matches[0]
    if len(owner) != 1 or not token_phrase(['chance'],WORD.findall(normalize(original['text']))):
        raise ValueError('example does not have an unambiguous lemma and POS owner')
    entry = owner[0][0]
    links = add_attributions(manifest,entry,USAGE_SNAPSHOT,original,translation)
    manifest['corpus_version'] += '-usage-pilot'
    manifest[USAGE_EXTENSION] = dict(version=1,entries={entry:links})


def add_attributions(manifest,entry,snapshot,original,translation,relationship=()):
    modification = usage_modification(entry,str(original['id']),str(translation['id']),original['text'],
                                      translation['text'],relationship=relationship)
    mods = manifest['modifications']
    if modification not in mods:
        mods.append(modification)
    used = mods.index(modification)
    links = {}
    for language, sentence in (('en',original),('ru',translation)):
        policy = dict(source=USAGE_SOURCE,snapshot=snapshot,source_url=USAGE_URL,
                      license_id=sentence['license'],terms_url=USAGE_LICENSES[sentence['license']],
                      attribution='Tatoeba · '+sentence['attribution_name'])
        if policy not in manifest['policies']:
            manifest['policies'].append(policy)
        index = manifest['policies'].index(policy)
        locator = f"sentences/{sentence['id']}/{language}"
        credit = hashlib.sha256(json.dumps(dict(policy,locator=locator,modification=modification),
                                            sort_keys=True).encode()).hexdigest()
        manifest['attributions'][credit] = [index,locator,used]
        links[language] = dict(text=sentence['text'],evidence=credit)
    source = [USAGE_SOURCE,snapshot,USAGE_URL]
    if source not in manifest['sources']:
        manifest['sources'].append(source)
        manifest['sources'].sort()
    return links


def add_curated_examples(manifest,db,decisions,captures):
    if manifest['schema_version']!=3 or USAGE_EXTENSION in manifest or not 1<=len(decisions)<=1000:
        raise ValueError('expected a bilingual research package and a bounded decision list')
    required = {'entry','capture','original','translation','relationship','token','pos','reason'}
    selected = {}
    used = set()
    for row in decisions:
        if (set(row)!=required or not isinstance(row['reason'],str) or not row['reason'].strip()):
            raise ValueError('example decision requires an explicit rationale and exact provenance')
        entry, capture = row['entry'],row['capture']
        owner = db.execute("SELECT search_key,pos FROM entry WHERE id=? AND language='en'",(entry,)).fetchone()
        if not owner or capture not in captures:
            raise ValueError('missing English article or pinned capture')
        relationship = usage_relationship(db,entry,owner,row)
        identity = (row['original'],row['translation'])
        matches = [(en,ru) for en,ru in pairs(captures[capture]) if identity==(en['id'],ru['id'])]
        if len(matches)!=1 or (entry,identity[0]) in used:
            raise ValueError('decision must select one unique directly linked sentence pair')
        original,translation = matches[0]
        if not usage_token_matches(relationship[2],original['text']):
            raise ValueError('unrelated sentence')
        used.add((entry,identity[0]))
        selected.setdefault(entry,[]).append((relationship,capture,original,translation))
    result = {}
    for entry,rows in selected.items():
        if len(rows)>8:
            raise ValueError('at most eight examples per article')
        # Rank only reviewed matches. Prefer the lemma and balanced standalone examples.
        rows.sort(key=lambda row:(row[0][1]!='lemma',abs(len(WORD.findall(row[2]['text']))-8),
                                  row[2]['id'],row[3]['id']))
        result[entry] = [dict(add_attributions(manifest,entry,USAGE_CAPTURES[capture],en,ru,relationship),
                              pos=relationship[0],relationship=relationship[1],token=relationship[2])
                         for relationship,capture,en,ru in rows]
    manifest['corpus_version'] += '-usage-v2'
    manifest[USAGE_EXTENSION] = dict(version=2,entries=result)
    return dict(articles=len(result),examples=sum(map(len,result.values())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base',type=Path)
    parser.add_argument('capture',type=Path,help='v1 API capture or directory of pinned v2 captures')
    parser.add_argument('output',type=Path)
    parser.add_argument('original',type=int,nargs='?')
    parser.add_argument('translation',type=int,nargs='?')
    parser.add_argument('--decisions',type=Path)
    args = parser.parse_args()
    for path,kind in ((args.base,'processed'),(args.capture,'interim'),(args.output,'processed')):
        if not path.resolve().is_relative_to((DATA/kind).resolve()):
            parser.error(f'use a path under research/data/{kind}')
    if args.output.exists():
        parser.error('expected a fresh output path')
    manifest = validate_research_pair(args.base)
    with connect(args.base/'dictionary.sqlite','ro') as db:
        if args.decisions:
            if not args.decisions.resolve().is_relative_to((DATA/'review').resolve()) or args.decisions.stat().st_size>512*1024:
                parser.error('use bounded local decisions under research/data/review')
            decisions = json.loads(args.decisions.read_text(encoding='utf-8'))
            captures = {name:read_capture(args.capture/f'{name}.json',USAGE_CAPTURES[name])
                        for name in {row['capture'] for row in decisions}}
            stats = add_curated_examples(manifest,db,decisions,captures)
        else:
            add_example(manifest,db,read_capture(args.capture,USAGE_SNAPSHOT),args.original,args.translation)
            stats = dict(articles=1,examples=1)
    with TemporaryDirectory(dir=args.output.parent) as temporary:
        candidate = Path(temporary)/'candidate'
        candidate.mkdir()
        copyfile(args.base/'dictionary.sqlite',candidate/'dictionary.sqlite')
        with (candidate/'manifest.json').open('w',encoding='utf-8',newline='\n') as stream:
            json.dump(manifest,stream,ensure_ascii=True,sort_keys=True,separators=(',',':'))
            stream.write('\n')
        validate_research_pair(candidate)
        candidate.rename(args.output)
    print(json.dumps(dict(stats,manifest_sha256=checksum(args.output/'manifest.json'))))


def read_capture(path,expected):
    if path.stat().st_size>256*1024 or checksum(path)!=expected:
        raise ValueError('unexpected Tatoeba capture')
    return json.loads(path.read_text(encoding='utf-8'))


if __name__ == '__main__':
    main()
