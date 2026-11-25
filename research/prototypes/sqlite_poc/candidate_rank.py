"""Structural triage and ranking for local review. Scores never imply approval."""
import hashlib
import re
from collections import Counter

from prototype import normalize, snapshot_id

POS = {'noun','verb','adj','adv','phrase','proverb'}
FORM_TAGS = {'form-of','alt-of','no-gloss'}
CYRILLIC = re.compile(r'[А-Яа-яЁё]')
PHRASE = re.compile(r'^[А-Яа-яЁё]+(?:[- ][А-Яа-яЁё]+){0,3}$')


def triage(gloss, form_reference=False):
    value = gloss.strip()
    lower = value.casefold()
    if form_reference or lower.startswith(('форма ', 'мн. ч.', 'прош. вр.', 'форма.')):
        return 'form-reference', 'exclude', None, 'form reference or placeholder'
    if not CYRILLIC.search(value):
        return 'unsuitable', 'exclude', None, 'no Russian script found'
    if ';' in value or '◆' in value:
        return 'mixed_gloss', 'specialist', None, 'multiple segments or context marker'
    if any(char in value for char in ',/()[]:'):
        return 'unclear', 'specialist', None, 'alternatives, qualifier or boundary marker'
    if PHRASE.fullmatch(value) and not lower.startswith(('тот ', 'то ', 'который ', 'действие ', 'состояние ')):
        return 'candidate_translation', 'standard', value, 'single short Russian phrase'
    return 'unclear', 'specialist', None, 'verify meaning and distinguish definition from translation'


def evaluate(record, text):
    if not isinstance(record,dict) or record.get('lang_code') != 'en' or not text(record.get('word')):
        return 'entry_or_language', None
    pos = record.get('pos')
    if not isinstance(pos,str) or pos not in POS:
        return 'pos', None
    senses = record.get('senses')
    if not isinstance(senses,list) or not senses or any(not isinstance(s,dict) for s in senses):
        return 'malformed_senses', None
    standard = specialist = examples = eligible = 0
    for sense in senses:
        tags = sense.get('tags',[])
        glosses = sense.get('glosses',[])
        contextual = sense.get('examples',[])
        if (not isinstance(tags,list) or any(not isinstance(tag,str) for tag in tags)
                or not isinstance(glosses,list) or any(not isinstance(gloss,str) for gloss in glosses)
                or not isinstance(contextual,list) or any(not isinstance(ex,dict) for ex in contextual)):
            return 'malformed_nested', None
        if FORM_TAGS.intersection(tags) or sense.get('form_of') or sense.get('alt_of'):
            continue
        priorities = [triage(g)[1] for g in glosses if text(g)]
        if not priorities or all(priority == 'exclude' for priority in priorities):
            continue
        eligible += 1
        standard += priorities == ['standard']
        specialist += priorities != ['standard']
        examples += any(text(ex.get('text')) is not None for ex in contextual)
    if not eligible:
        return 'no_content_or_form_only', None
    if not standard:
        return 'ambiguity_only', None
    sounds = record.get('sounds',[])
    if not isinstance(sounds,list) or any(not isinstance(sound,dict) for sound in sounds):
        return 'malformed_sounds',None
    ipa = any(text(sound.get('ipa')) is not None for sound in sounds)
    # Prefer an evidenced sense. Seeded tie-breaking avoids presenting input order as frequency.
    score = 3 * bool(examples) + 2 * ipa + bool(pos == 'phrase' or ' ' in record['word'])
    return None, (score,eligible,specialist,pos)


def choose(stream, limit, seed, text, decode, stats):
    best = {}
    for number, raw in enumerate(stream,1):
        if number > 250_000:
            raise ValueError('pilot scan exceeds 250,000 physical lines')
        stats['lines_scanned'] += 1
        try:
            record = decode(raw)
        except (ValueError, RecursionError):
            stats['scan_invalid_json'] += 1
            continue
        fault, result = evaluate(record,text)
        if fault:
            stats['selection_'+fault] += 1
            continue
        score,eligible,specialist,pos = result
        spelling = normalize(record['word'])
        tie = hashlib.sha256(f'{seed}:{spelling}'.encode()).hexdigest()
        ranking = (-score, bool(specialist), tie, number)
        previous = best.get(spelling)
        if previous is None or ranking < previous[0]:
            best[spelling] = (ranking,eligible,specialist,pos)
    stats['eligible_normalized_spellings'] = len(best)
    selected = sorted(best.values())[:limit]
    stats['selected_multiple_usable_senses'] = sum(row[1]>1 for row in selected)
    stats['selected_has_complex_sense'] = sum(row[2]>0 for row in selected)
    for _,_,_,pos in selected:
        stats['selected_pos_'+pos] += 1
    return {row[0][3] for row in selected}
