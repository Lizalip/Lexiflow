"""Append an independent, screened FreeDict RU–EN subset to an existing EN–RU package."""
import argparse
import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path
from shutil import copyfile
from tempfile import TemporaryDirectory
from uuid import NAMESPACE_URL, uuid5

from corpus_enrichment import RUS_ENG_SHA
from prototype import normalize
from release_package import RESEARCH_POLICY, TABLES, POLICY_FIELDS, checksum, connect, expand_attributions, write_package
from research_package import DATA, DIRECT_URL, LICENSE, MARKER, SOURCE, TERMS, VERSION, validate_research_pair
from research_reverse import verified_direct
from research_reverse_aliases import stress_key
from sense_context import direct_child

RUSSIAN = re.compile(r"[А-Яа-яЁё]+(?:[ -][А-Яа-яЁё]+)*\Z")
ENGLISH_LETTER = re.compile('[A-Za-z]')


def source_id(kind,locator):
    return str(uuid5(NAMESPACE_URL,f'lexiflow/research/{RUS_ENG_SHA}/{kind}/{locator}'))


def screening(source,existing_keys,size):
    candidates = defaultdict(list)
    for row in source.execute("""
        SELECT e.id,e.spelling,e.pos,e.search_key,ee.locator,s.id,s.position,se.locator,g.body,ge.locator
        FROM entry e JOIN sense s ON s.entry_id=e.id JOIN gloss g ON g.sense_id=s.id
        JOIN evidence ee ON ee.id=e.evidence_id JOIN evidence se ON se.id=s.evidence_id
        JOIN evidence ge ON ge.id=g.evidence_id WHERE e.lang='ru' AND g.lang='en' AND g.role='source_gloss'
        AND ee.rights!='excluded' AND se.rights!='excluded' AND ge.rights!='excluded'
        ORDER BY e.search_key,e.id,s.position,g.position
    """):
        _,word,pos,key,_,_,_,_,text,_ = row
        if (pos in ('n','v','adj','adv','pronoun','preposition','conjunction','interjection','particle')
                and 1<=len(word)<=80 and RUSSIAN.fullmatch(word) and len(text)<=240
                and ENGLISH_LETTER.search(text) and '://' not in text
                and not text.casefold().startswith(('see ','form of '))):
            candidates[key].append(row)
    # Retain an overlapping stratum for richer usage and prioritize additions elsewhere.
    ordered = sorted(candidates,key=lambda key:(len(key),' ' in key,key))
    common = [key for key in ordered if stress_key(key) in existing_keys][:size//5]
    missing = sorted((key for key in candidates if stress_key(key) not in existing_keys),
                     key=lambda key:(len(key)>10,hashlib.sha256(('direct-selection-v1/'+key).encode()).digest()))
    return candidates,sorted(common+missing[:size-len(common)])


def insert_direct(source,dest,rows,attrs,notes):
    entries,senses,parents,seen = {},{},{},set()

    def ref(locator):
        row = dict(source=SOURCE,snapshot=RUS_ENG_SHA,source_url=DIRECT_URL,license_id=LICENSE,
                   terms_url=TERMS,attribution=SOURCE+' · RU–EN '+VERSION,modification=MARKER,locator=locator)
        key = hashlib.sha256(json.dumps(row,sort_keys=True).encode()).hexdigest()
        attrs[key] = row
        return key

    for eid,word,pos,key,el,sid,position,sl,text,tl in rows:
        if eid not in entries:
            entries[eid] = source_id('entry',el)
            dest.execute("INSERT INTO entry VALUES (?,?,?,?,?,'ru')",(entries[eid],word,pos,key,ref(el)))
        if sid not in senses:
            senses[sid] = source_id('sense',sl)
            parents[sid] = eid,sl
            dest.execute('INSERT INTO sense VALUES (?,?,?,?)',(senses[sid],entries[eid],position,ref(sl)))
        if (sid,text) in seen:
            continue
        seen.add((sid,text))
        credit = ref(tl)
        dest.execute("""INSERT INTO translation(sense_id,wording,search_key,attribution_id,origin_attribution_id,language)
                     VALUES (?,?,?,?,?,'en')""",(senses[sid],text,normalize(text),credit,credit))
    for eid,parent,child,definition,text in source.execute("""
        SELECT s.entry_id,json_extract(s.raw_tags,'$.parent_sense_id'),se.locator,ge.locator,g.body
        FROM gloss g JOIN sense s ON s.id=g.sense_id JOIN evidence se ON se.id=s.evidence_id
        JOIN evidence ge ON ge.id=g.evidence_id WHERE g.lang='ru' AND g.role='definition'
        AND se.rights!='excluded' AND ge.rights!='excluded' ORDER BY g.id
    """):
        owner = entries.get(eid)
        origin = parents.get(parent)
        if (owner and origin and origin[0]==eid and len(notes.get(owner,()))<3 and len(text)<=1000
                and '://' not in text and direct_child(origin[1],child,definition)):
            notes.setdefault(owner,[]).append(dict(text=text,definition=ref(definition)))


def build(source,base,out,size):
    verified_direct(source)
    manifest = validate_research_pair(base)
    if manifest['schema_version']!=2:
        raise ValueError('expected a single-source V2 base')
    attrs = expand_attributions(manifest['policies'],manifest['attributions'],manifest.get('modifications'))
    notes = manifest.get('source_entry_notes',{}).get('entries',{}).copy()
    with connect(base/'dictionary.sqlite','ro') as old:
        existing = {stress_key(key) for key, in old.execute('SELECT DISTINCT search_key FROM translation')}
    candidates,keys = screening(source,existing,size)

    def populate(dest):
        dest.execute('ATTACH DATABASE ? AS prior',((base/'dictionary.sqlite').resolve().as_uri()+'?mode=ro',))
        for table in (*TABLES,'lexical_metadata'):
            if table in ('entry','translation'):
                dest.execute(f"INSERT INTO {table} SELECT *,? FROM prior.{table}",('en' if table=='entry' else 'ru',))
            else:
                dest.execute(f'INSERT INTO {table} SELECT * FROM prior.{table}')
        insert_direct(source,dest,(row for key in keys for row in candidates[key]),attrs,notes)
        return attrs,{}

    write_package(source,out,f'research-bilingual-freedict-{VERSION}-{size}',3,True,populate,
                  RESEARCH_POLICY,notes,shared_modifications=True)
    validate_research_pair(out)
    with connect(out/'dictionary.sqlite','ro') as db:
        return dict(entries=db.execute('SELECT language,count(DISTINCT search_key),count(*) FROM entry GROUP BY language').fetchall(),
            senses=db.execute('SELECT e.language,count(*) FROM sense s JOIN entry e ON e.id=s.entry_id GROUP BY e.language').fetchall(),
            translations=db.execute('SELECT language,count(*) FROM translation GROUP BY language').fetchall(),
            manifest_sha256=checksum(out/'manifest.json'),database_bytes=(out/'dictionary.sqlite').stat().st_size,
            manifest_bytes=(out/'manifest.json').stat().st_size)


def supplement_rows(candidates, selection, current):
    if not isinstance(selection,list) or not 1<=len(selection)<=500:
        raise ValueError('expected a bounded list of Russian lemma/POS pairs')
    seen = set()
    rows = []
    for pair in selection:
        if not isinstance(pair,list) or len(pair)!=2 or not all(isinstance(value,str) for value in pair):
            raise ValueError('invalid lemma/POS pair')
        key,pos = pair
        matches = [row for row in candidates.get(key,()) if row[2]==pos]
        if (key,pos) in seen or (key,pos) in current or not matches:
            raise ValueError('duplicate, already installed, or unsupported lemma/POS candidate')
        seen.add((key,pos))
        rows.extend(matches)
    return rows


def supplement(source,base,out,selection):
    verified_direct(source)
    manifest = validate_research_pair(base)
    if manifest['schema_version']!=3 or manifest['package_format']!=3 or out.exists():
        raise ValueError('expected a compact V3 base and fresh output path')
    candidates,_ = screening(source,set(),500)
    with connect(base/'dictionary.sqlite','ro') as prior:
        current = set(prior.execute("SELECT DISTINCT search_key,pos FROM entry WHERE language='ru'"))
    rows = supplement_rows(candidates,selection,current)
    counts_before = manifest['counts'].copy()
    attrs = {}
    notes = manifest.setdefault('source_entry_notes',dict(version=1,entries={}))['entries']
    with TemporaryDirectory(dir=out.parent) as temporary:
        stage = Path(temporary)/'candidate'
        stage.mkdir()
        database = stage/'dictionary.sqlite'
        copyfile(base/'dictionary.sqlite',database)
        with connect(database,'rw') as dest:
            dest.execute('PRAGMA foreign_keys=ON')
            with dest:
                insert_direct(source,dest,rows,attrs,notes)
            for table in ('entry','sense','translation'):
                manifest['counts'][table] = dest.execute(f'SELECT count(*) FROM {table}').fetchone()[0]
            headwords = dest.execute("SELECT count(DISTINCT search_key) FROM entry WHERE language='ru'").fetchone()[0]
        for ref,row in attrs.items():
            if ref in manifest['attributions']:
                raise ValueError('supplement overlaps existing attribution')
            policy = {key:row[key] for key in POLICY_FIELDS}
            manifest['attributions'][ref] = [manifest['policies'].index(policy),row['locator'],
                                            manifest['modifications'].index(row['modification'])]
        manifest['database_sha256'] = checksum(database)
        manifest['corpus_version'] += '-ru-supplement'
        with (stage/'manifest.json').open('x',encoding='utf-8',newline='\n') as stream:
            json.dump(manifest,stream,ensure_ascii=True,sort_keys=True,separators=(',',':'))
            stream.write('\n')
        validate_research_pair(stage)
        stage.rename(out)
    return dict(lemma_pos_pairs=len(selection),russian_headwords=headwords,
                added={table:manifest['counts'][table]-counts_before[table]
                       for table in ('entry','sense','translation')},
                manifest_sha256=checksum(out/'manifest.json'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('direct',type=Path)
    parser.add_argument('archive',type=Path)
    parser.add_argument('base',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--supplement',type=Path,help='add the frequency list from the local selection report')
    args = parser.parse_args()
    if not all(p.resolve().is_relative_to((DATA/'processed').resolve()) for p in (args.direct,args.base,args.output)):
        parser.error('use local processed paths')
    if checksum(args.archive)!=RUS_ENG_SHA:
        parser.error('source does not match pinned RU–EN TEI')
    selection = None
    if args.supplement:
        if (not args.supplement.resolve().is_relative_to((DATA/'review').resolve())
                or args.supplement.stat().st_size>128*1024):
            parser.error('use a bounded, local research selection')
        selection = json.loads(args.supplement.read_text(encoding='utf-8'))['frequency']
    with connect(args.direct,'ro') as src:
        src.execute('BEGIN')
        result = build(src,args.base,args.output,5000) if selection is None else supplement(src,args.base,args.output,selection)
        print(json.dumps(result,indent=2))
