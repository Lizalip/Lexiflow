"""Export the pinned FreeDict lexical snapshot to a local research-only package."""
import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from time import perf_counter
from uuid import NAMESPACE_URL, uuid5

from candidate_rank import subtlex_frequency
from corpus_enrichment import LICENSE, SOURCE, TERMS, URL
from prototype import integrity, normalize
from release_package import RESEARCH_POLICY, checksum, connect, validate_package, write_package
from sense_context import direct_child

SNAPSHOT = 'f3dadcc29d7df6e77edb13b98a2ba2c3fac002852eddd8b557637f0b13d39c39'
FREQUENCY_SNAPSHOT = '67e595da1b399d2a21e25a1466a8d9f242f21a219c6bbbd3e089c4779ad83856'
VERSION = '2025.11.23'
DATA = Path(__file__).resolve().parents[2]/'data'
LETTER_RU = re.compile('[А-Яа-яЁё]')
WORD_EN = re.compile(r"[A-Za-z][A-Za-z]*(?:[ ’'-][A-Za-z]+)*\Z")
REFERENCE = re.compile(r'^(?:см\.|см |смотри |перенаправление|форма слова|https?://)',re.IGNORECASE)
MARKER = RESEARCH_POLICY+': unchanged source wording in a technically screened subset'

CORE = """
SELECT e.id,e.spelling,e.pos,e.search_key,ee.locator,s.id,s.position,se.locator,g.body,ge.locator,
       ee.rights,se.rights,ge.rights
FROM entry e JOIN sense s ON s.entry_id=e.id JOIN gloss g ON g.sense_id=s.id
JOIN evidence ee ON ee.id=e.evidence_id JOIN evidence se ON se.id=s.evidence_id
JOIN evidence ge ON ge.id=g.evidence_id
WHERE e.lang='en' AND g.lang='ru' AND g.role='source_gloss'
  AND ee.release_id=? AND se.release_id=? AND ge.release_id=?
ORDER BY e.search_key,e.id,s.position,g.position
"""


def verified_release(src):
    stored = src.execute("""
        SELECT r.id,s.name,r.sha256,r.version FROM release r JOIN source s ON s.id=r.source_id
    """).fetchall()
    if len(stored) != 1 or stored[0][1:] != (SOURCE,SNAPSHOT,VERSION):
        raise ValueError('staging does not identify the pinned FreeDict snapshot')
    integrity(src)
    return stored[0][0]


def screen(rows):
    groups = defaultdict(list)
    reasons = Counter()
    for row in rows:
        _,spelling,pos,key,_,_,_,_,wording,_,*rights = row
        if 'excluded' in rights:
            reasons['explicitly_excluded_evidence'] += 1
        elif pos in ('pn','prefix','suffix','symbol') or len(spelling) > 80 or not WORD_EN.fullmatch(spelling):
            reasons['headword_or_pos_outside_scope'] += 1
        elif not LETTER_RU.search(wording) or len(wording) > 240 or REFERENCE.match(wording.strip()):
            reasons['translation_outside_scope'] += 1
        else:
            groups[key].append(row)
    return groups,reasons


def select(groups, frequency, size):
    if size is None:
        return set(groups),0
    # Expression priority uses component counts as a review heuristic.
    # SUBTLEX counts individual word forms and supplies no phrase frequency.
    expressions = []
    for key,rows in groups.items():
        terms = key.split()
        if 2 <= len(terms) <= 6 and any(row[2] in ('v','phraseologicalUnit','proverb') for row in rows):
            count = min(frequency.get(term,0) for term in terms)
            if count >= 100:
                expressions.append((-count,len(terms),key))
    phrases = {key for _,_,key in sorted(expressions)[:size//20]}
    preferred = sorted(groups.keys()-phrases,key=lambda key:(-frequency.get(key,0),' ' in key,key))
    return phrases | set(preferred[:size-len(phrases)]),len(phrases)


def stable_id(kind,locator):
    return str(uuid5(NAMESPACE_URL,f'lexiflow/research/{SNAPSHOT}/{kind}/{locator}'))


def populate(src,dest,groups,keys,release_id,entry_notes):
    attrs = {}
    entries,senses,sense_origins = {},{},{}
    translations = set()

    def attribution(locator):
        row = dict(source=SOURCE,snapshot=SNAPSHOT,source_url=URL,license_id=LICENSE,
                   terms_url=TERMS,attribution=SOURCE+' · EN–RU '+VERSION,modification=MARKER,locator=locator)
        ref = hashlib.sha256(json.dumps(row,sort_keys=True).encode()).hexdigest()
        attrs[ref] = row
        return ref

    for key in sorted(keys):
        for e,spelling,pos,_,el,s,position,sl,wording,wl,*_ in groups[key]:
            if e not in entries:
                entries[e] = stable_id('entry',el)
                dest.execute('INSERT INTO entry VALUES (?,?,?,?,?)',(entries[e],spelling,pos,key,attribution(el)))
            if s not in senses:
                senses[s] = stable_id('sense',sl)
                sense_origins[s] = (e,sl)
                dest.execute('INSERT INTO sense VALUES (?,?,?,?)',(senses[s],entries[e],position,attribution(sl)))
            candidate = (s,wording)
            if candidate in translations:
                continue
            translations.add(candidate)
            ref = attribution(wl)
            dest.execute("""INSERT INTO translation(sense_id,wording,search_key,attribution_id,origin_attribution_id)
                            VALUES (?,?,?,?,?)""",(senses[s],wording,normalize(wording),ref,ref))

    # A definition is used only when its own sense has an eligible Russian translation.
    # Parent/child pointers and same-spelling rows never transfer wording.
    for owner, table, column, value, kind, language, condition in (
        (entries,'pronunciation','entry_id','ipa','ipa','en',"t.ipa IS NOT NULL"),
        (senses,'gloss','sense_id','body','definition','en',"t.role='definition' AND t.lang='en'"),
    ):
        for source_id,text,locator in src.execute(f"""
                SELECT t.{column},t.{value},ev.locator FROM {table} t
                JOIN evidence ev ON ev.id=t.evidence_id
                WHERE {condition} AND ev.release_id=? AND ev.rights!='excluded' ORDER BY t.id
        """,(release_id,)):
            if (source_id not in owner or not text.strip() or len(text) > (250 if kind == 'ipa' else 1500)
                    or REFERENCE.match(text.strip()) or '://' in text):
                continue
            dest.execute(f"""INSERT INTO lexical_metadata({column},kind,value,lang,attribution_id)
                             VALUES (?,?,?,?,?)""",(owner[source_id],kind,text,language,attribution(locator)))
    for source_id,spelling,key,locator in src.execute("""
            SELECT f.entry_id,f.spelling,f.search_key,ev.locator FROM form f
            JOIN evidence ev ON ev.id=f.evidence_id
            WHERE ev.release_id=? AND ev.rights!='excluded' ORDER BY f.id
    """,(release_id,)):
        if source_id in entries and WORD_EN.fullmatch(spelling):
            dest.execute('INSERT INTO form(entry_id,spelling,search_key,attribution_id) VALUES (?,?,?,?)',
                         (entries[source_id],spelling,key,attribution(locator)))
    # Keep a bounded sample of genuine definitions as independent entry-level notes.
    # Attribution still names the original child definition, never its translation parent.
    definition_counts = Counter()
    for entry,child,owner,definition,body,parent in src.execute("""
            SELECT s.entry_id,s.id,oe.locator,de.locator,g.body,
                   json_extract(s.raw_tags,'$.parent_sense_id')
            FROM gloss g JOIN sense s ON s.id=g.sense_id JOIN evidence oe ON oe.id=s.evidence_id
            JOIN evidence de ON de.id=g.evidence_id
            WHERE g.lang='en' AND g.role='definition' AND oe.release_id=? AND de.release_id=?
              AND oe.rights!='excluded' AND de.rights!='excluded' ORDER BY g.id
    """,(release_id,release_id)):
        if parent not in sense_origins or child in senses or definition_counts[entry] >= 3:
            continue
        parent_entry,parent_locator = sense_origins[parent]
        if (entry != parent_entry or not direct_child(parent_locator,owner,definition) or not body.strip()
                or len(body) > 1000 or REFERENCE.match(body.strip()) or '://' in body):
            continue
        entry_notes.setdefault(entries[entry],[]).append(dict(text=body,definition=attribution(definition)))
        definition_counts[entry] += 1
    return attrs,{}


def build(src, frequency, package, size, observe=None):
    started = perf_counter()
    release_id = verified_release(src)
    groups,reasons = screen(src.execute(CORE,(release_id,)*3))
    keys,expressions = select(groups,frequency,size)
    name = f'research-freedict-{VERSION}-{size or "all"}'
    notes = {}
    result = write_package(src,package,name,2,True,
                           lambda dest: populate(src,dest,groups,keys,release_id,notes),RESEARCH_POLICY,notes,
                           observe=observe)
    return dict(spellings=len(keys),eligible_spellings=len(groups),reserved_expressions=expressions,
                filtered_translation_rows=dict(reasons),counts=result['counts'],
                entry_definition_notes=sum(map(len,notes.values())),entries_with_definition_notes=len(notes),
                database_bytes=(package/'dictionary.sqlite').stat().st_size,
                manifest_bytes=(package/'manifest.json').stat().st_size,
                manifest_sha256=checksum(package/'manifest.json'),
                generation_and_validation_seconds=round(perf_counter()-started,3))


def validate_research_pair(package):
    if package.is_symlink() or any((package/name).is_symlink() for name in ('manifest.json','dictionary.sqlite')):
        raise ValueError('research package must be a local file pair')
    result = validate_package(package)
    if (result.get('distribution_policy') != RESEARCH_POLICY or result['sources'] != [[SOURCE,SNAPSHOT,URL]]
            or any(row.get('license_id') != LICENSE or row.get('terms_url') != TERMS for row in result['policies'])
            or result['counts']['example'] or result['counts']['example_translation'] or 'sense_context' in result):
        raise ValueError('package does not match the documented research source policy')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='mode',required=True)
    export = commands.add_parser('export')
    export.add_argument('staging',type=Path)
    export.add_argument('archive',type=Path)
    export.add_argument('frequency',type=Path)
    export.add_argument('output',type=Path)
    export.add_argument('--size',choices=('5000','10000','20000','30000','all'),default='5000')
    commands.add_parser('validate').add_argument('package',type=Path)
    args = parser.parse_args()
    processed = (DATA/'processed').resolve()
    if args.mode == 'validate':
        if not args.package.resolve().is_relative_to(processed):
            parser.error('package must be under research/data/processed/')
        started = perf_counter()
        result = validate_research_pair(args.package)
        print(json.dumps(dict(counts=result['counts'],validation_seconds=round(perf_counter()-started,3))))
        return
    if not all(path.resolve().is_relative_to(processed) for path in (args.staging,args.output)):
        parser.error('staging and output must be under research/data/processed/')
    if checksum(args.archive) != SNAPSHOT or checksum(args.frequency) != FREQUENCY_SNAPSHOT:
        parser.error('source or frequency snapshot does not match the pinned digest')
    with connect(args.staging,'ro') as src:
        src.execute('BEGIN')
        size = None if args.size == 'all' else int(args.size)
        print(json.dumps(build(src,subtlex_frequency(args.frequency),args.output,size),indent=2))


if __name__ == '__main__':
    main()
