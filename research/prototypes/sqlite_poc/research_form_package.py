"""Attach independently sourced English forms to unambiguous Research articles."""
import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from contextlib import ExitStack
from pathlib import Path
from shutil import copyfile
from tempfile import TemporaryDirectory

from release_package import (FORM_CAPTURE, FORM_SOURCE, FORM_TERMS, FORM_URL, checksum, connect,
                             form_modification)
from research_package import DATA, validate_research_pair

PROBES = set('went gone ran took taken wrote written children mice better worse bought thought understood'.split())
POS = {'VERB':'v','NOUN':'n','ADJ':'adj','ADV':'adv'}


def annotations(lines,probes=PROBES):
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
            if ((probes is not None and word not in probes) or lemma==word or upos not in POS
                    or not all(re.fullmatch('[a-z]{1,40}',part) for part in (word,lemma))):
                continue
            features = set(cells[5].split('|'))
            feature = None
            if upos=='VERB':
                for required,label in (
                    ({'Tense=Past','VerbForm=Part'},'Past participle'),
                    ({'Tense=Past','VerbForm=Fin'},'Past'),
                    ({'Tense=Pres','VerbForm=Part'},'Present participle'),
                    ({'VerbForm=Ger'},'Gerund'),
                    ({'Tense=Pres','VerbForm=Fin','Person=3','Number=Sing'},'Third-person singular'),
                ):
                    if required<=features:
                        feature = label
                        break
            elif upos=='NOUN' and 'Number=Plur' in features:
                feature = 'Plural'
            elif upos in ('ADJ','ADV'):
                if 'Degree=Cmp' in features:
                    feature = 'Comparative'
                elif 'Degree=Sup' in features:
                    feature = 'Superlative'
            if feature:
                yield lemma,POS[upos],word,feature,f'{sentence}/{token}'


def add_forms(manifest,db,samples,*,all_forms=False):
    if manifest['schema_version']!=3:
        raise ValueError('expected a bilingual base')
    evidence = {}
    for split,lines in samples.items():
        for lemma,pos,word,feature,locator in annotations(lines,None if all_forms else PROBES):
            key = lemma,pos,word,feature
            evidence[key] = min(evidence.get(key,(split,locator)),(split,locator))
    owners = defaultdict(list)
    for lemma,pos,owner in db.execute("SELECT search_key,pos,id FROM entry WHERE language='en'"):
        owners[lemma,pos].append(owner)
    current = set(db.execute("""SELECT f.entry_id,f.search_key,m.value FROM form f
        JOIN lexical_metadata m ON m.form_id=f.id WHERE m.kind='grammar'"""))
    seen_surfaces, seen_lemmas, links = set(),set(),set()
    metrics = Counter()
    categories = Counter()
    for (lemma,pos,word,feature),(split,locator) in sorted(evidence.items()):
        possible = owners[lemma,pos]
        if len(possible)!=1:
            metrics['ambiguous' if possible else 'no_owner'] += 1
            continue
        owner = possible[0]
        if (owner,word,feature) in current:
            metrics['preserved'] += 1
            continue
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
        seen_surfaces.add(word)
        seen_lemmas.add(lemma)
        links.add((word,owner))
        categories[feature] += 1
        metrics['inserted'] += 1
    inserted = metrics['inserted']
    manifest['sources'].sort()
    manifest['corpus_version'] += '-ud-expanded' if all_forms else '-ud-forms'
    manifest['counts']['form'] += inserted
    manifest['counts']['lexical_metadata'] += inserted
    available = {word for word, in db.execute('SELECT DISTINCT search_key FROM form')}
    return dict(forms=inserted,total_form_rows=manifest['counts']['form'],distinct_surfaces=len(seen_surfaces),
                lemmas=len(seen_lemmas),form_to_entry_links=len(links),categories=dict(categories),
                candidates=len(evidence),ambiguous_exclusions=metrics['ambiguous'],
                no_owner=metrics['no_owner'],preserved_analyses=metrics['preserved'],
                uncovered=sorted(PROBES-available))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('sample_dir',type=Path)
    parser.add_argument('--all',action='store_true',help='screen all attestations from the pinned splits')
    args = parser.parse_args()
    if (args.output.exists() or not all(path.resolve().is_relative_to((DATA/'processed').resolve())
            for path in (args.base,args.output)) or not args.sample_dir.resolve().is_relative_to((DATA/'interim').resolve())):
        parser.error('use fresh output and local research artifacts')
    samples = {split:args.sample_dir/f'UD_English-EWT-r2.16-{split}.conllu' for split in FORM_CAPTURE}
    for split,source in samples.items():
        if source.stat().st_size>2*1024**2 or checksum(source)!=FORM_CAPTURE[split]:
            raise ValueError('unexpected UD EWT snapshot')
    manifest = validate_research_pair(args.base)
    with TemporaryDirectory(dir=args.output.parent) as temporary:
        candidate = Path(temporary)/'candidate'
        candidate.mkdir()
        target = candidate/'dictionary.sqlite'
        copyfile(args.base/'dictionary.sqlite',target)
        with connect(target,'rw') as db:
            with ExitStack() as captures:
                streams = {split:captures.enter_context(path.open(encoding='utf-8')) for split,path in samples.items()}
                stats = add_forms(manifest,db,streams,all_forms=args.all)
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
