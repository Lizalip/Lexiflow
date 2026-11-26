"""Verify the frozen Research sense sample and create an offline proposal workspace."""
import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

from candidate_rank import subtlex_frequency
from editor_workspace import write_html
from prototype import normalize
from release_package import checksum, connect
from research_package import (DATA, FREQUENCY_SNAPSHOT, SNAPSHOT, stable_id,
                              validate_research_pair, verified_release)

LABELS = {
    'plausible_translation': ('Правдоподобный перевод','none'),
    'valid_but_uncommon': ('Редкое употребление','low'),
    'rare_specialized': ('Специальное употребление','low'),
    'duplicate': ('Дубликат','low'),
    'redundant_variants': ('Избыточные варианты','low'),
    'presentation_issue': ('Представление','low'),
    'missing_context': ('Недостающий контекст','medium'),
    'requires_context': ('Требуется контекст','medium'),
    'overly_broad': ('Слишком широкий перевод','medium'),
    'overly_narrow': ('Слишком узкий перевод','medium'),
    'incomplete_translation': ('Неполный перевод','medium'),
    'unresolved': ('Нерешённое соответствие','high'),
    'mixed_meanings': ('Смешанные значения','high'),
    'pos_mismatch': ('Возможное расхождение POS','high'),
    'potential_pos_mismatch': ('Возможное расхождение POS','high'),
    'potential_semantic_mismatch': ('Возможное расхождение смысла','high'),
    'misleading_definition': ('Возможная проблема определения','high'),
}


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def match_assessments(sample, batches):
    identity = {row['sense_id']:row for row in sample}
    if len(identity)!=len(sample):
        raise ValueError('duplicate sample sense')
    matched = {}
    for batch, assessments in batches:
        for row in assessments:
            key = row['sense_id']
            original = identity.get(key)
            if original is None or key in matched:
                raise ValueError('unknown or duplicate assessed sense')
            rationale = row.get('evidence') or row.get('reason')
            if (row['assessment'] not in LABELS or not isinstance(rationale,str) or not rationale.strip()
                    or row.get('human_review') is not False or row.get('method')!='AI-assisted preliminary'
                    or any(row[k]!=original[k] for k in ('entry_id','sense_locator','stratum'))
                    or row.get('source_snapshot',row.get('snapshot'))!=original['snapshot']):
                raise ValueError('invalid assessment or provenance')
            if 'original_wording' in row:
                if row['original_wording']!=original['translations'] or row['pos']!=original['pos']:
                    raise ValueError('changed evaluated wording or POS')
            elif sorted(row['translation_locators'])!=sorted(t['locator'] for t in original['translations']):
                raise ValueError('changed translation locators')
            matched[key] = dict(row,batch=batch,rationale=rationale)
    if identity.keys()!=matched.keys():
        raise ValueError('incomplete audit')
    return matched


def source_rows(source, sample, release):
    sense = sample['sense_locator']
    results = {}
    for row in source.execute("""
        SELECT e.id,s.id,ee.locator,ve.locator,g.body,ve.rights,e.spelling,e.pos
        FROM sense s JOIN evidence se ON se.id=s.evidence_id
        JOIN entry e ON e.id=s.entry_id JOIN evidence ee ON ee.id=e.evidence_id
        JOIN gloss g ON g.sense_id=s.id JOIN evidence ve ON ve.id=g.evidence_id
        WHERE se.release_id=? AND se.locator=? AND ee.release_id=se.release_id
        AND ve.release_id=se.release_id AND g.lang='ru' AND g.role='source_gloss'
    """,(release,sense)):
        entry_id,sense_id,entry_at,locator,text,rights,spelling,pos = row
        if (locator in results or stable_id('entry',entry_at)!=sample['entry_id']
                or stable_id('sense',sense)!=sample['sense_id']
                or spelling!=sample['headword'] or pos!=sample['pos']):
            raise ValueError('ambiguous or mismatched source owner')
        results[locator] = dict(entry_id=entry_id,sense_id=sense_id,locator=locator,
                                snapshot_sha256=SNAPSHOT,candidate=text,translation_rights=rights)
    return results


def audit(source, package, manifest, sample, assessments, frequency):
    release = verified_release(source)
    notes = manifest.get('source_entry_notes',{}).get('entries',{})
    counts = defaultdict(Counter)
    by_category = defaultdict(lambda: defaultdict(Counter))
    rows = []
    for item in sample:
        entry, sense = item['entry_id'], item['sense_id']
        assessment = assessments[sense]
        context = notes.get(entry,[])
        owners = source_rows(source,item,release)
        stored = package.execute("""
            SELECT e.id,e.spelling,e.pos,s.position,s.attribution_id,
                   (SELECT count(*) FROM sense WHERE entry_id=e.id)
            FROM sense s JOIN entry e ON e.id=s.entry_id WHERE s.id=? AND e.language='en'
        """,(sense,)).fetchone()
        if (not stored or item['snapshot']!=SNAPSHOT
                or stored[:4]!=(entry,item['headword'],item['pos'],item['position'])
                or manifest['attributions'][stored[4]][1]!=item['sense_locator']
                or bool(context)!=item['entry_note_available']):
            raise ValueError('sample owner or context no longer matches the package')
        expected = [(t['locator'],t['text']) for t in item['translations']]
        actual = [(manifest['attributions'][ref][1],text) for text,ref in package.execute(
            'SELECT wording,attribution_id FROM translation WHERE sense_id=? ORDER BY id',(sense,))]
        if sorted(expected)!=sorted(actual) or any(
                at not in owners or owners[at]['candidate']!=text for at,text in expected):
            raise ValueError('sample translation changed or cannot be exported')
        category = assessment['assessment']
        label,severity = LABELS[category]
        word_count = frequency.get(normalize(item['headword']),0)
        stratum = item['stratum']
        for key,value in dict(pos=item['pos'],sample_stratum=stratum,senses_per_entry=stored[5],
                translations=len(expected),source_definition=bool(context),
                frequency='10000+' if word_count>=10000 else '51–9999' if word_count>50
                else '1–50' if word_count else 'unlisted',category=category,severity=severity).items():
            counts[key][str(value)] += 1
            if key not in ('category','severity'):
                by_category[key][str(value)][category] += 1
        counts['uncertainty']['present' if assessment.get('uncertainty') else 'missing'] += 1
        counts['proposed_action']['present' if assessment.get('proposed_action') else 'missing'] += 1
        for at,_ in expected:
            original = owners[at]
            row = dict(original,headword=item['headword'],pos=item['pos'],source='FreeDict EN–RU',
                       context=[dict(text=d['text'],sense_id='Контекст статьи',
                                     locator=manifest['attributions'][d['definition']][1],rights='research-local')
                                for d in context],
                       alternatives=len(expected),source_alternatives=[text for _,text in expected],
                       priority='specialist' if severity in ('high','medium') else 'standard',
                       severity=severity,frequency=word_count,reason=stratum,assessment_category=category,
                       assessment_label=label,assessment_reason=assessment['rationale'],
                       assessment_uncertainty=assessment.get('uncertainty'),
                       preliminary_suggestion=assessment.get('proposed_action'),
                       research_owner=f'UUID статьи {entry} · UUID значения {sense} · {item["sense_locator"]}',
                       required_decision='human_proposal',status='pending')
            rows.append(row)
    order = {'high':0,'medium':1,'low':2,'none':3}
    rows.sort(key=lambda row:(order[row['severity']],-row['frequency'],row['headword'],row['locator']))
    return dict(senses=len(sample),translation_rows=len(rows),human_decisions=0,
                counts=counts,by_category=by_category), rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sample_dir',type=Path)
    parser.add_argument('package',type=Path)
    parser.add_argument('staging',type=Path)
    parser.add_argument('frequency',type=Path)
    parser.add_argument('output',type=Path)
    args = parser.parse_args()
    for path,kind in ((args.sample_dir,'review'),(args.output,'review'),(args.package,'processed'),
                      (args.staging,'processed'),(args.frequency,'interim')):
        if not path.resolve().is_relative_to((DATA/kind).resolve()):
            parser.error(f'use paths under research/data/{kind}')
    sample = read(args.sample_dir/'sample.json')
    if len(sample)!=280 or checksum(args.frequency)!=FREQUENCY_SNAPSHOT:
        parser.error('expected the frozen sample of 280 and pinned SUBTLEX snapshot')
    assessments = match_assessments(sample,((p.name,read(p)) for p in sorted(
        args.sample_dir.glob('preliminary-assessment*.json'))))
    manifest = validate_research_pair(args.package)
    with connect(args.staging,'ro') as source, connect(args.package/'dictionary.sqlite','ro') as db:
        metrics,rows = audit(source,db,manifest,sample,assessments,subtlex_frequency(args.frequency))
    args.output.mkdir()
    with (args.output/'queue.json').open('x',encoding='utf-8') as output:
        json.dump(rows,output,ensure_ascii=False,indent=2)
    write_html(args.output/'index.html',rows)
    with (args.output/'summary.json').open('x',encoding='utf-8') as output:
        json.dump(metrics,output,ensure_ascii=False,indent=2)
    print(json.dumps(metrics,ensure_ascii=True,indent=2))


if __name__ == '__main__':
    main()
