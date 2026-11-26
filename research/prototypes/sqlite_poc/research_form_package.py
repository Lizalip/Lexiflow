"""Attach independently sourced English forms to unambiguous Research articles."""
import argparse
import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path
from shutil import copyfile
from tempfile import TemporaryDirectory

from release_package import (FORM_CAPTURE, FORM_SOURCE, FORM_TERMS, FORM_URL, checksum, connect,
                             form_modification)
from research_package import DATA, validate_research_pair

PROBES = set('went gone ran took taken wrote written children mice better worse bought thought understood'.split())
POS = {'VERB':'v','NOUN':'n','ADJ':'adj','ADV':'adv'}


def annotations(lines):
    sentence = None
    for row in lines:
        if row.startswith('# sent_id = '):
            sentence = row.removeprefix('# sent_id = ').strip()
        elif not row.strip():
            sentence = None
        else:
            cells = row.rstrip('\r\n').split('\t')
            if not sentence or len(cells)!=10 or not cells[0].isdigit():
                continue
            token, word, lemma, upos = cells[:4]
            word, lemma = word.lower(),lemma.lower()
            if word not in PROBES or lemma==word or not re.fullmatch('[a-z]{1,40}',lemma) or upos not in POS:
                continue
            features = set(cells[5].split('|'))
            if upos=='VERB' and 'Tense=Past' in features:
                feature = {'VerbForm=Part':'Past participle','VerbForm=Fin':'Past'}
                for label, value in feature.items():
                    if label in features:
                        yield lemma,POS[upos],word,value,f'{sentence}/{token}'
            elif upos=='NOUN' and 'Number=Plur' in features:
                yield lemma,POS[upos],word,'Plural',f'{sentence}/{token}'
            elif upos in ('ADJ','ADV') and 'Degree=Cmp' in features:
                yield lemma,POS[upos],word,'Comparative',f'{sentence}/{token}'


def add_forms(manifest,db,samples):
    if manifest['schema_version']!=3 or any(source[0]==FORM_SOURCE for source in manifest['sources']):
        raise ValueError('expected a bilingual base without UD forms')
    grouped = defaultdict(list)
    for split,lines in samples.items():
        for lemma,pos,word,feature,locator in annotations(lines):
            grouped[lemma,pos,word,feature].append((split,locator))
    inserted = 0
    included = set()
    for (lemma,pos,word,feature),evidence in sorted(grouped.items()):
        owners = db.execute("SELECT id FROM entry WHERE language='en' AND search_key=? AND pos=?",
                            (lemma,pos)).fetchall()
        if len(owners)!=1:
            continue
        owner = owners[0][0]
        split, locator = min(evidence)
        policy = dict(source=FORM_SOURCE,snapshot=FORM_CAPTURE[split],source_url=FORM_URL,
                      license_id='CC BY-SA 4.0',terms_url=FORM_TERMS,
                      attribution='UD English EWT r2.16 annotations · Stanford University · Silveira et al.')
        if policy not in manifest['policies']:
            manifest['policies'].append(policy)
            manifest['sources'].append([FORM_SOURCE,FORM_CAPTURE[split],FORM_URL])
        modification = form_modification(lemma,pos,word,feature)
        index = len(manifest['modifications'])
        manifest['modifications'].append(modification)
        location = f'{split}/{locator}'
        canonical = dict(policy,locator=location,modification=modification)
        ref = hashlib.sha256(json.dumps(canonical,sort_keys=True).encode()).hexdigest()
        manifest['attributions'][ref] = [manifest['policies'].index(policy),location,index]
        form = db.execute('INSERT INTO form(entry_id,spelling,search_key,attribution_id) VALUES (?,?,?,?)',
                          (owner,word,word,ref)).lastrowid
        db.execute("""INSERT INTO lexical_metadata(form_id,kind,value,lang,attribution_id)
                      VALUES (?,'grammar',?,'en',?)""",(form,feature,ref))
        included.add(word)
        inserted += 1
    if not inserted:
        raise ValueError('no unambiguous forms')
    manifest['sources'].sort()
    manifest['corpus_version'] += '-ud-forms'
    manifest['counts']['form'] += inserted
    manifest['counts']['lexical_metadata'] += inserted
    return dict(forms=inserted,distinct_surfaces=len(included),uncovered=sorted(PROBES-included))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('sample_dir',type=Path)
    args = parser.parse_args()
    if (args.output.exists() or not all(path.resolve().is_relative_to((DATA/'processed').resolve())
            for path in (args.base,args.output)) or not args.sample_dir.resolve().is_relative_to((DATA/'interim').resolve())):
        parser.error('use fresh output and local research artifacts')
    samples = {}
    for split,digest in FORM_CAPTURE.items():
        source = args.sample_dir/f'UD_English-EWT-r2.16-{split}.conllu'
        if source.stat().st_size>2*1024**2 or checksum(source)!=digest:
            raise ValueError('unexpected UD EWT snapshot')
        samples[split] = source.read_text(encoding='utf-8').splitlines()
    manifest = validate_research_pair(args.base)
    with TemporaryDirectory(dir=args.output.parent) as temporary:
        candidate = Path(temporary)/'candidate'
        candidate.mkdir()
        target = candidate/'dictionary.sqlite'
        copyfile(args.base/'dictionary.sqlite',target)
        with connect(target,'rw') as db:
            stats = add_forms(manifest,db,samples)
            db.commit()
        manifest['database_sha256'] = checksum(target)
        with (candidate/'manifest.json').open('w',encoding='utf-8',newline='\n') as stream:
            json.dump(manifest,stream,ensure_ascii=True,sort_keys=True,separators=(',',':'))
            stream.write('\n')
        validate_research_pair(candidate)
        candidate.rename(args.output)
    print(json.dumps(dict(stats,manifest_sha256=checksum(args.output/'manifest.json'))))


if __name__ == '__main__':
    main()
