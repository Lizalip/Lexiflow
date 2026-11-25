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
