"""Generate aggregate quality indicators and a separate local review worksheet."""
import argparse
import hashlib
import json
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

from candidate_rank import subtlex_frequency
from prototype import normalize
from release_package import checksum, connect
from research_package import CORE, DATA, FREQUENCY_SNAPSHOT, WORD_EN, validate_research_pair, verified_release
from research_evaluation import HOLDOUT

SEED = 'lexiflow-expand-20261125-v1'
ORDERING = 'run get take make set go put bank light mean right present work lead point'.split()
PLAIN_PUNCTUATION = set(",;:.-()–—…·/?!'’“”«»")


def scalar(db,sql):
    return db.execute(sql).fetchone()[0]


def histogram(db,table,key):
    groups = db.execute(f'SELECT count(*) FROM {table} GROUP BY {key}')
    return dict(sorted(Counter(count for count, in groups).items()))


def flags(text):
    result = []
    if len(text) <= 2:
        result.append('at_most_two_codepoints')
    if len(text) > 48:
        result.append('over_48_codepoints')
    if any(unicodedata.category(c).startswith('P') and c not in PLAIN_PUNCTUATION for c in text):
        result.append('unusual_punctuation')
    if any(unicodedata.category(c).startswith('C') for c in text):
        result.append('control_or_format_character')
    if text != text.strip() or any(c in text for c in ('\ufffd','\x00')):
        result.append('possible_malformed_record')
    return result


def priority(item):
    return hashlib.sha256(f"{SEED}/{item['sense_id']}".encode()).digest()


def audit(db,manifest,frequency,quota):
    note_ids = manifest.get('source_entry_notes',{}).get('entries',{})
    attrs = manifest['attributions']
    translations = defaultdict(list)
    duplicate_normalized = 0
    risks = Counter()
    for sid, wording,key,origin in db.execute(
            'SELECT sense_id,wording,search_key,attribution_id FROM translation ORDER BY sense_id,id'):
        translations[sid].append((wording,key,attrs[origin][1],flags(wording)))
        risks.update(translations[sid][-1][3])
    specialized = {normalize(word) for category in ('science','academic','business','computing','mathematics',
          'statistics','machine_learning') for word in HOLDOUT[category].split(',')}
    buckets = defaultdict(list)
    identical = Counter()
    first = defaultdict(list)
    for eid,word,key,pos,sid,order,ref in db.execute("""
            SELECT e.id,e.spelling,e.search_key,e.pos,s.id,s.position,s.attribution_id
            FROM entry e JOIN sense s ON s.entry_id=e.id ORDER BY e.search_key,e.id,s.position
    """):
        rows = translations[sid]
        keys = {row[1] for row in rows}
        duplicate_normalized += len(rows)-len(keys)
        identical[(key,pos,tuple(sorted(keys)))] += 1
        if key in ORDERING:
            first[key].append(dict(spelling=word,pos=pos,position=order,sense_locator=attrs[ref][1],
                                   translations=[row[0] for row in rows]))
        count = frequency.get(key,0)
        item = dict(entry_id=eid,sense_id=sid,headword=word,pos=pos,position=order,
                    sense_locator=attrs[ref][1],snapshot=manifest['sources'][0][1],
                    translations=[dict(text=row[0],locator=row[2],flags=row[3]) for row in rows],
                    entry_note_available=eid in note_ids,assessment='pending')
        if count >= 10000 and len(rows)==1:
            buckets['frequent_single_translation'].append(item)
        if len(keys) >= 4:
            buckets['multiple_translations'].append(item)
        if key in ORDERING:
            buckets['ambiguous_priority_headword'].append(item)
        if (' ' in key or pos in ('phraseologicalUnit','proverb')):
            buckets['expression'].append(item)
        if 0 < count <= 50 or key in specialized:
            buckets['rare_or_specialized_candidate'].append(item)
        if any(len(candidate)>2 and ' ' not in candidate for candidate in keys):
            buckets['reverse_search_candidate'].append(item)
        if eid in note_ids:
            buckets['entry_with_nested_definition'].append(item)
    chosen = set()
    sample = []
    for group in sorted(buckets,key=lambda name:(len(buckets[name]),name)):
        available = (item for item in sorted(buckets[group],key=priority) if item['sense_id'] not in chosen)
        selected = []
        for item in available:
            selected.append(dict(stratum=group,**item))
            chosen.add(item['sense_id'])
            if len(selected)==quota:
                break
        sample.extend(selected)
    metrics = dict(
        counts=manifest['counts'], normalized_headwords=scalar(db,'SELECT count(DISTINCT search_key) FROM entry'),
        pos_entries=dict(db.execute("SELECT coalesce(nullif(pos,''),'(missing)'),count(*) FROM entry GROUP BY pos")),
        entries_per_headword=histogram(db,'entry','search_key'), senses_per_entry=histogram(db,'sense','entry_id'),
        senses_per_headword=histogram(db,'entry JOIN sense ON sense.entry_id=entry.id','search_key'),
        translations_per_sense=histogram(db,'translation','sense_id'),
        entries_with_source_notes=len(note_ids), distinct_normalized_translations=scalar(db,'SELECT count(DISTINCT search_key) FROM translation'),
        same_sense_repeated_normalized_wordings=duplicate_normalized,
        same_headword_pos_translation_set_clusters=sum(n>1 for n in identical.values()),
        translation_risk_counts=dict(risks), sampling_frame={name:len(rows) for name,rows in buckets.items()},
        selection_counts=dict(Counter(row['stratum'] for row in sample)),
        multiword_headwords=scalar(db,"SELECT count(DISTINCT search_key) FROM entry WHERE instr(search_key,' ')>0"),
        expression_headwords=scalar(db,"SELECT count(DISTINCT search_key) FROM entry WHERE pos IN ('phraseologicalUnit','proverb')"),
        senses_with_attached_definition=scalar(db,"SELECT count(DISTINCT sense_id) FROM lexical_metadata WHERE kind='definition'"),
        entries_with_ipa=scalar(db,"SELECT count(DISTINCT entry_id) FROM lexical_metadata WHERE kind='ipa'"),
        headwords_with_ipa=scalar(db,"SELECT count(DISTINCT entry.search_key) FROM lexical_metadata JOIN entry ON entry.id=entry_id WHERE kind='ipa'"),
        ordering_sense_counts={headword:len(senses) for headword,senses in first.items()})
    return metrics,sample,first


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package',type=Path)
    parser.add_argument('frequency',type=Path)
    parser.add_argument('local_review',type=Path)
    parser.add_argument('--staging',type=Path,help='count source-side exclusions')
    args = parser.parse_args()
    if not args.package.resolve().is_relative_to((DATA/'processed').resolve()) or not args.local_review.resolve().is_relative_to((DATA/'review').resolve()):
        parser.error('use local processed packages and a fresh directory under research/data/review')
    if checksum(args.frequency)!=FREQUENCY_SNAPSHOT:
        parser.error('frequency archive does not match pinned digest')
    manifest = validate_research_pair(args.package)
    with connect(args.package/'dictionary.sqlite','ro') as db:
        metrics,sample,ordering = audit(db,manifest,subtlex_frequency(args.frequency),40)
    if args.staging:
        if not args.staging.resolve().is_relative_to((DATA/'processed').resolve()):
            parser.error('use a local FreeDict staging database under research/data/processed')
        with connect(args.staging,'ro') as db:
            release = verified_release(db)
            excluded = Counter()
            for row in db.execute(CORE,(release,)*3):
                _,spelling,pos,_,_,_,_,_,wording,_,*rights = row
                excluded['proper_name'] += pos=='pn'
                excluded['unsupported_pos'] += pos in ('prefix','suffix','symbol')
                excluded['unsupported_spelling_shape'] += not bool(WORD_EN.fullmatch(spelling)) or len(spelling)>80
                excluded['excluded_rights_flag'] += 'excluded' in rights
                excluded['reference_like_wording'] += wording.casefold().lstrip().startswith(('см.','см ','смотри ','http:','https:'))
            metrics['source_candidate_risk_flags_nonexclusive'] = dict(excluded)
    args.local_review.mkdir(exist_ok=False)
    for name,value in (('sample.json',sample),('sense-ordering.json',ordering)):
        (args.local_review/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(metrics,ensure_ascii=True,indent=2))


if __name__ == '__main__':
    main()
