"""Read diagnostic inputs from the saved notebook without executing its cells."""
import ast
import json
from pathlib import Path

NOTEBOOK = Path(__file__).resolve().parents[2] / 'notebooks' / '01_source_audit.ipynb'


def diagnostic_spellings(path: Path = NOTEBOOK) -> set[str]:
    cells = json.loads(path.read_text(encoding='utf-8'))['cells']
    groups = []
    for cell in cells:
        if cell['cell_type'] != 'code' or 'vocabulary_groups =' not in ''.join(cell['source']):
            continue
        for stmt in ast.parse(''.join(cell['source'])).body:
            if isinstance(stmt, ast.Assign) and any(
                isinstance(target, ast.Name) and target.id == 'vocabulary_groups' for target in stmt.targets
            ):
                groups.append(ast.literal_eval(stmt.value))
    if len(groups) != 1:
        raise ValueError('expected one diagnostic vocabulary assignment')
    targets = [word for group in groups[0].values() for word in group]
    if len(targets) != 24 or len(set(targets)) != 24:
        raise ValueError('expected 24 distinct notebook spellings')
    return set(targets)


def english_edition_shape(record):
    """Inspect a Wiktextract object independently of the Russian-edition adapter."""
    if not isinstance(record, dict) or record.get('lang_code') != 'en':
        raise ValueError('expected an English-language Wiktextract record')
    senses = record.get('senses')
    if not isinstance(senses, list) or not all(isinstance(sense, dict) for sense in senses):
        raise ValueError('expected a list of sense objects')
    translations = record.get('translations', [])
    if not isinstance(translations, list) or not all(isinstance(item, dict) for item in translations):
        raise ValueError('expected a list of translation objects')
    return {
        'senses': len(senses),
        'senses_with_gloss_array': sum(isinstance(sense.get('glosses'), list) for sense in senses),
        'top_level_translation_candidates': len(translations),
    }


def english_edition_candidates(record):
    """Project a single English Wiktextract record for review, preserving local array paths."""
    english_edition_shape(record)
    if not isinstance(record.get('word'),str) or not record['word'].strip():
        raise ValueError('expected a headword')
    if not isinstance(record.get('pos'),str) or not record['pos'].strip():
        raise ValueError('expected a part of speech')

    def items(owner, key):
        raw = owner.get(key, [])
        if not isinstance(raw,list):
            raise ValueError(f'{key} must be a list')
        return raw

    def texts(owner, key, path):
        for index,value in enumerate(items(owner,key)):
            if not isinstance(value,str) or not value.strip():
                raise ValueError(f'{key} contains invalid text')
            yield (f'{path}/{key}/{index}',value)

    senses = []
    for index,sense in enumerate(record['senses']):
        path = f'/senses/{index}'
        senses.append(dict(locator=path, definitions=list(texts(sense,'glosses',path)),
                           raw_tags=list(texts(sense,'tags',path))))
    ipa = []
    for index,sound in enumerate(items(record,'sounds')):
        if not isinstance(sound,dict):
            raise ValueError('invalid sound')
        if 'ipa' in sound:
            value = sound['ipa']
            if not isinstance(value,str) or not value.strip():
                raise ValueError('invalid IPA')
            ipa.append(dict(locator=f'/sounds/{index}/ipa',value=value))
    translations = []
    for index,item in enumerate(items(record,'translations')):
        if item.get('code') != 'ru':
            continue
        if not isinstance(item.get('word'),str) or not item['word'].strip():
            raise ValueError('invalid translation')
        hint = item.get('sense')
        if hint is not None and not isinstance(hint,str):
            raise ValueError('invalid source sense hint')
        translations.append(dict(locator=f'/translations/{index}',word=item['word'],source_hint=hint))
    return dict(word=record['word'],pos=record['pos'],senses=senses,ipa=ipa,
                unaligned_translations=translations,rights='pending')
