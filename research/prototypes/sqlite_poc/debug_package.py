"""Generate an Android debug asset with artificial, deliberately marked lexical data."""
import argparse
import json
import shutil
import sqlite3
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import NAMESPACE_URL, uuid5

import identity
from kaikki import import_sample
from prototype import create_database
from release_package import STAGING_METADATA, export_package, record_rights
from review_queue import IDENTITY, apply, tasks
from sense_context import apply_decisions

FIXTURES = [
    dict(word='zeta-demo', pos='noun', forms=[dict(form='zeta-demos')], senses=[
        dict(glosses=['демо-объект'], examples=[dict(text='[Fixture A]', translation='[Пример A]')]),
        dict(glosses=['демо-символ']),
    ]),
    dict(word='zeta-demo', pos='verb', senses=[dict(glosses=['демо-действие'])]),
    dict(word='zeta demo phrase', pos='phrase', senses=[dict(glosses=['демо-выражение'])]),
]

PREVIEW_HEADWORD = 'aurora-demo'
PREVIEW_FIXTURES = [
    dict(word=PREVIEW_HEADWORD, pos='noun', forms=[dict(form='aurora-demos'),dict(form='aurora-demo’s')], senses=[
        dict(glosses=['условный световой знак','искусственная отметка для проверки макета'], examples=[
            dict(text='[Preview] The aurora-demo stood beside the paper lantern.',
                 translation='[Макет] Световой знак стоял рядом с бумажным фонарём.'),
            dict(text='[Preview] Three small aurora-demos formed a line.',
                 translation='[Макет] Три небольших знака стояли в ряд.'),
        ]),
        dict(glosses=['световая последовательность для учебной демонстрации']),
        dict(glosses=['условный маркер в описании места']),
    ]),
    dict(word=PREVIEW_HEADWORD, pos='verb', senses=[dict(glosses=['отмечать условным светом'])]),
    dict(word='biv', pos='interjection', senses=[dict(glosses=['сигнал для макета'])]),
    dict(word='glasswing-demo', pos='adjective', senses=[dict(glosses=['прозрачный на просвет'])]),
    dict(word='paper lantern cue', pos='phrase', senses=[dict(glosses=['условный сигнал бумажного фонаря'])]),
    dict(word='illustrative-snow-globe-with-an-extended-label', pos='noun', senses=[
        dict(glosses=['длинное условное название предмета для проверки переноса текста и границ строки'])
    ]),
]


def make(output, version=1, *, context=False, preview=False):
    if preview and (version != 2 or not context):
        raise ValueError('design preview requires schema 2 and context')
    if context and version != 2:
        raise ValueError('context fixture requires schema 2')
    with TemporaryDirectory() as temp, closing(sqlite3.connect(':memory:')) as src, closing(sqlite3.connect(':memory:')) as registry:
        source = Path(temp)/'artificial.jsonl'
        fixtures = PREVIEW_FIXTURES if preview else FIXTURES
        source.write_text('\n'.join(json.dumps(dict(record,lang_code='en')) for record in fixtures)+'\n', encoding='utf-8')
        create_database(src)
        sha = import_sample(src,source)['snapshot_sha256']
        src.row_factory = sqlite3.Row
        rows = list(tasks(src))
        src.row_factory = None
        apply(src, [{**{key: task[key] for key in IDENTITY}, 'classification':'candidate_translation',
                     'proposed_translation':task['gloss'], 'decision':'approved','reviewer':'synthetic fixture',
                     'method':'human','reason':'artificial technical test marker, no real editorial approval'} for task in rows])
        src.execute("UPDATE source SET name='LexiFlow artificial technical fixtures'")
        src.commit()
        example_row = next(task for task in rows if task['headword'] == PREVIEW_HEADWORD and task['pos'] == 'noun') if preview else rows[0]
        if version == 2:
            src.executescript(STAGING_METADATA)
            entry, sense = example_row['entry_id'], example_row['sense_id']
            form = src.execute("SELECT id FROM form WHERE entry_id=?",(entry,)).fetchone()[0]
            for index,(owner,kind,value,lang) in enumerate([
                ((entry,None,None),'ipa','/əˈrɔːrə/' if preview else '/ˈzɛtə/','en'),
                ((entry,None,None),'pronunciation','[artificial variant]','en'),
                ((entry,None,None),'region','[artificial region]','en'),
                ((None,sense,None),'definition',
                 '[Preview definition] A deliberately invented light marker used to demonstrate how a longer '
                 'definition wraps next to multiple examples and a separate grammatical form. The fixture '
                 'exercises scrolling and type size in a narrow window.' if preview else '[Artificial technical definition]','en'),
                ((None,sense,None),'source_gloss','[Искусственная глосса]','ru'),
                ((None,sense,None),'style','[artificial register]','en'),
                ((None,sense,None),'cefr','A1','und'),
                ((None,None,form),'grammar','[artificial plural]','en'),
            ]):
                evidence = src.execute("INSERT INTO evidence(release_id,locator) VALUES (1,?)",
                                       (f'artificial/metadata/{index}',)).lastrowid
                src.execute("""
                    INSERT INTO lexical_metadata(entry_id,sense_id,form_id,kind,value,lang,evidence_id)
                    VALUES (?,?,?,?,?,?,?)
                """,(*owner,kind,value,lang,evidence))
            src.commit()
        if context:
            root = example_row['locator'].rsplit('/glosses/',1)[0]
            owner = root+'/sense/0'
            definition = owner+'/def/0'
            with src:
                owner_evidence = src.execute('INSERT INTO evidence(release_id,locator) VALUES (1,?)',
                                             (owner,)).lastrowid
                def_evidence = src.execute('INSERT INTO evidence(release_id,locator) VALUES (1,?)',
                                           (definition,)).lastrowid
                src.execute('INSERT INTO sense VALUES (?,?,?,?,?)',
                            ('fixture-context',example_row['entry_id'],4 if preview else 3,owner_evidence,
                             json.dumps(dict(parent_sense_id=example_row['sense_id']))))
                src.execute("""INSERT INTO gloss(sense_id,lang,body,role,position,evidence_id)
                            VALUES ('fixture-context','en','[Artificial nested context]','definition',1,?)""",
                            (def_evidence,))
            apply_decisions(src,[dict(parent_id=example_row['sense_id'],snapshot=sha,definition_locator=definition,
                                      reviewer='fixture',reason='artificial context only',decision='approved',method='human')])
        for locator, in src.execute('SELECT locator FROM evidence').fetchall():
            record_rights(src,sha,locator,source_url='https://example.invalid/lexiflow-fixtures',
                          terms_url='https://example.invalid/lexiflow-fixtures',
                          license_id='artificial-fixture', attribution='LexiFlow synthetic fixtures',
                          modification='synthetic',decision='cleared',reason='technical fixture only',reviewer='fixture')
        registry.executescript(identity.SCHEMA)
        for kind in ('entry','sense'):
            for local_id, locator in src.execute(f'SELECT t.id,e.locator FROM {kind} t JOIN evidence e ON e.id=t.evidence_id'):
                stable = str(uuid5(NAMESPACE_URL,('lexiflow-preview-v1/' if preview else 'lexiflow-debug-v1/')+locator))
                identity.bind(registry,kind,sha,local_id,stable,reason='synthetic fixture')
        suffix = '-context' if context else ''
        name = 'synthetic-design-preview-v1' if preview else f'synthetic-debug-v{version}{suffix}'
        result = export_package(src,registry,output,name,version=version,compact=context)
        shutil.copyfile(Path(__file__).with_name('normalization_vectors.json'), output/'normalization_vectors.json')
        return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    parser.add_argument('--schema-version',type=int,choices=(1,2),default=1)
    parser.add_argument('--sense-context',action='store_true')
    parser.add_argument('--design-preview',action='store_true',help='use the separately marked, richer V2 preview')
    args = parser.parse_args()
    version = 2 if args.design_preview else args.schema_version
    print(json.dumps(make(args.output,version,context=args.sense_context or args.design_preview,
                          preview=args.design_preview)['counts']))
