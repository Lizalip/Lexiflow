"""Export one verified Tatoeba pair as an article-level Research usage example."""
import argparse
import hashlib
import json
from pathlib import Path
from shutil import copyfile
from tempfile import TemporaryDirectory

from prototype import normalize
from release_package import (USAGE_EXTENSION, USAGE_LICENSES, USAGE_SNAPSHOT, USAGE_SOURCE, USAGE_URL,
                             checksum, connect, usage_modification)
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
    modification = usage_modification(entry,str(original_id),str(translation_id),original['text'],translation['text'])
    manifest['modifications'].append(modification)
    used = len(manifest['modifications'])-1
    links = {}
    for language, sentence in (('en',original),('ru',translation)):
        policy = dict(source=USAGE_SOURCE,snapshot=USAGE_SNAPSHOT,source_url=USAGE_URL,
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
    manifest['sources'] = sorted([*manifest['sources'],[USAGE_SOURCE,USAGE_SNAPSHOT,USAGE_URL]])
    manifest['corpus_version'] += '-usage-pilot'
    manifest[USAGE_EXTENSION] = dict(version=1,entries={entry:links})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base',type=Path)
    parser.add_argument('capture',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('original',type=int)
    parser.add_argument('translation',type=int)
    args = parser.parse_args()
    for path,kind in ((args.base,'processed'),(args.capture,'interim'),(args.output,'processed')):
        if not path.resolve().is_relative_to((DATA/kind).resolve()):
            parser.error(f'use a path under research/data/{kind}')
    if args.output.exists() or args.capture.stat().st_size > 256*1024 or checksum(args.capture) != USAGE_SNAPSHOT:
        parser.error('expected a pinned capture and fresh output path')
    manifest = validate_research_pair(args.base)
    with connect(args.base/'dictionary.sqlite','ro') as db:
        add_example(manifest,db,json.loads(args.capture.read_text(encoding='utf-8')),
                    args.original,args.translation)
    with TemporaryDirectory(dir=args.output.parent) as temporary:
        candidate = Path(temporary)/'candidate'
        candidate.mkdir()
        copyfile(args.base/'dictionary.sqlite',candidate/'dictionary.sqlite')
        with (candidate/'manifest.json').open('w',encoding='utf-8',newline='\n') as stream:
            json.dump(manifest,stream,ensure_ascii=True,sort_keys=True,separators=(',',':'))
            stream.write('\n')
        validate_research_pair(candidate)
        candidate.rename(args.output)
    print(json.dumps(dict(articles=1,examples=1,manifest_sha256=checksum(args.output/'manifest.json'))))


if __name__ == '__main__':
    main()
