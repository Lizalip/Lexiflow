"""Measure candidate FreeDict/Kaikki alignments while keeping meanings independent."""
import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

from prototype import integrity, normalize
from release_package import connect
from research_diagnostics import KAIKKI_SNAPSHOT
from research_evaluation import HOLDOUT
from research_package import DATA, validate_research_pair
from research_reverse_aliases import stress_key

POS = {'noun':'n','verb':'v','adj':'adj','adv':'adv','pron':'pronoun','prep':'preposition',
       'conj':'conjunction','intj':'interjection','interj':'interjection','num':'numeral'}
FORM_TAGS = {'past','plural','participle','comparative','superlative','alternative','present'}


def tags(raw):
    value = json.loads(raw)
    return value if isinstance(value,dict) else {}


def ipa_key(value):
    return value.strip().strip('/[]').strip()


def analyze(free,manifest,kaikki):
    if set(kaikki.execute('SELECT sha256 FROM release')) != {(KAIKKI_SNAPSHOT,)}:
        raise ValueError('unexpected Kaikki staging snapshot')
    integrity(kaikki)
    keyed = defaultdict(list)
    free_ipa = defaultdict(set)
    for entry,value in free.execute("SELECT entry_id,value FROM lexical_metadata WHERE kind='ipa'"):
        free_ipa[entry].add(ipa_key(value))
    source_glosses = defaultdict(set)
    for sense,value in kaikki.execute("SELECT sense_id,body FROM gloss WHERE lang='ru' AND role='source_gloss'"):
        source_glosses[sense].add(normalize(value))
    by_example = defaultdict(list)
    for sense,locator,citation,translated in kaikki.execute("""
        SELECT x.sense_id,e.locator,x.citation,
               EXISTS(SELECT 1 FROM example_translation t WHERE t.example_id=x.id)
        FROM example x JOIN evidence e ON e.id=x.evidence_id WHERE x.lang='en' AND e.rights!='excluded'
    """):
        by_example[sense].append(dict(locator=locator,has_external_citation=bool(citation),translated=bool(translated)))
    by_form = defaultdict(list)
    for entry,key,raw,locator in kaikki.execute("""SELECT f.entry_id,f.search_key,f.raw_metadata,e.locator
            FROM form f JOIN evidence e ON e.id=f.evidence_id WHERE e.rights!='excluded'"""):
        grammatical = FORM_TAGS.intersection(tags(raw).get('tags',[]))
        if grammatical:
            by_form[entry].append(dict(form_key=key,locator=locator,tags=sorted(grammatical)))
    by_ipa = defaultdict(dict)
    for entry,value,locator in kaikki.execute("""SELECT p.entry_id,p.ipa,e.locator FROM pronunciation p
            JOIN evidence e ON e.id=p.evidence_id WHERE p.ipa IS NOT NULL AND e.rights!='excluded'"""):
        by_ipa[entry].setdefault(ipa_key(value),[]).append(locator)
    for entry,key,pos,sense,locator,external,raw in kaikki.execute("""
            SELECT e.id,e.search_key,e.pos,s.id,se.locator,se.external_id,s.raw_tags
            FROM entry e JOIN sense s ON s.entry_id=e.id JOIN evidence se ON se.id=s.evidence_id
            JOIN evidence ee ON ee.id=e.evidence_id WHERE e.lang='en' AND se.rights!='excluded' AND ee.rights!='excluded'
    """):
        content = source_glosses[sense]
        keyed[key].append(dict(entry=entry,pos=POS.get(pos),sense=sense,locator=locator,external=external,
                               form_reference=bool(tags(raw).get('form_reference')),glosses=content,
                               accent_aliases={stress_key(value) for value in content}))
    free_glosses = defaultdict(set)
    for identity,value in free.execute('SELECT sense_id,search_key FROM translation'):
        free_glosses[identity].add(value)
    attrs = manifest['attributions']
    notes = manifest.get('source_entry_notes',{}).get('entries',{})
    signals = Counter()
    candidates = defaultdict(list)
    hit_degree = Counter()
    for entry,key,pos,sense,attr in free.execute("""
        SELECT e.id,e.search_key,e.pos,s.id,s.attribution_id FROM entry e JOIN sense s ON s.entry_id=e.id
    """):
        signals['free_senses'] += 1
        peers = keyed.get(key,())
        signals['same_headword_senses'] += bool(peers)
        peers = [other for other in peers if pos and other['pos']==pos]
        signals['same_pos_senses'] += bool(peers)
        literal = free_glosses[sense]
        folded = {stress_key(value) for value in literal}
        for other in peers:
            shared = literal & other['glosses']
            aliases = folded & other['accent_aliases']
            signals['same_pos_candidate_pairs'] += 1
            if not aliases:
                signals['no_translation_agreement_pairs'] += 1
                continue
            signals['literal_pairs'] += bool(shared)
            signals['only_stress_alias_pairs'] += not bool(shared)
            entry_other, id_other = other['entry'], other['sense']
            local_ipa = by_ipa[entry_other]
            row = dict(free_sense=sense,free_locator=attrs[attr][1],free_snapshot=manifest['sources'][0][1],
                       key=key,pos=pos,kaikki_sense=id_other,kaikki_locator=other['locator'],
                       kaikki_external_id=other['external'],kaikki_snapshot=KAIKKI_SNAPSHOT,
                       literal_overlaps=len(shared),stress_overlaps=len(aliases),
                       independent_freedict_definitions=len(notes.get(entry,())),
                       explicit_kaikki_form_reference=other['form_reference'],
                       forms=by_form[entry_other],known_ipa_overlap=len(local_ipa.keys() & free_ipa[entry]),
                       ipa_candidate_locators=[loc for ipa,locs in local_ipa.items() if ipa not in free_ipa[entry] for loc in locs],
                       examples=by_example[id_other],status='unverified')
            candidates[sense].append(row)
            hit_degree[id_other] += 1
    sample = []
    attested_forms = {form['form_key'] for forms in by_form.values() for form in forms}
    candidate_forms = set()
    for rows in candidates.values():
        for row in rows:
            mutual = len(rows)==1 and hit_degree[row['kaikki_sense']]==1
            row['unique_candidate_in_snapshot'] = mutual
            signals['unique_candidate_pairs'] += mutual
            signals['ambiguous_candidate_pairs'] += not mutual
            signals['pairs_with_grammatical_forms'] += bool(row['forms'])
            signals['pairs_with_other_ipa'] += bool(row['ipa_candidate_locators'])
            signals['pairs_with_identical_ipa'] += bool(row['known_ipa_overlap'])
            signals['pairs_with_translated_example'] += any(x['translated'] for x in row['examples'])
            candidate_forms.update(form['form_key'] for form in row['forms'])
            sample.append(row)
    sample.sort(key=lambda row:(not row['unique_candidate_in_snapshot'],not row['literal_overlaps'],
                    not any(row[field] for field in ('forms','ipa_candidate_locators','examples')),
                    hashlib.sha256(f"{row['free_sense']}/{row['kaikki_sense']}".encode()).digest()))
    inventory = dict((table,kaikki.execute(f'SELECT count(*) FROM {table}').fetchone()[0])
                     for table in ('entry','sense','form','pronunciation','example','example_translation'))
    inventory['normalized_headwords'] = len(keyed)
    inventory['ipa_rows_with_value'] = sum(len(locs) for values in by_ipa.values() for locs in values.values())
    inventory['ipa_rows_repeated_per_entry'] = inventory['ipa_rows_with_value']-sum(map(len,by_ipa.values()))
    inventory['examples_with_citation'] = sum(row['has_external_citation'] for rows in by_example.values() for row in rows)
    inventory['forms_with_explicit_grammar_tags'] = sum(map(len,by_form.values()))
    form_probes = {key:dict(in_kaikki=key in attested_forms,alignment_candidate=key in candidate_forms)
                   for key in HOLDOUT['inflected'].split(',')}
    return dict(inventory=inventory,counts=dict(signals),free_senses_with_any_translation_candidate=len(candidates),
                candidate_pairs=len(sample),unmatched_free_senses=signals['free_senses']-len(candidates),
                duplicate_candidate_pairs=len(sample)-len({(row['free_sense'],row['kaikki_sense']) for row in sample}),
                inflected_heldout_probes=form_probes,non_stable_source_ids_used_as_join_key=0,imported=0),sample[:80]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package',type=Path)
    parser.add_argument('kaikki',type=Path)
    parser.add_argument('sample',type=Path)
    args = parser.parse_args()
    if not all(path.resolve().is_relative_to((DATA/'processed').resolve()) for path in (args.package,args.kaikki)):
        parser.error('use local inputs under research/data/processed')
    if not args.sample.resolve().is_relative_to((DATA/'review').resolve()):
        parser.error('sample must stay under research/data/review')
    manifest = validate_research_pair(args.package)
    with connect(args.package/'dictionary.sqlite','ro') as free, connect(args.kaikki,'ro') as kaikki:
        result,sample = analyze(free,manifest,kaikki)
    with args.sample.open('x',encoding='utf-8',newline='\n') as stream:
        json.dump(sample,stream,ensure_ascii=False,indent=2)
        stream.write('\n')
    print(json.dumps(result,indent=2))


if __name__ == '__main__':
    main()
