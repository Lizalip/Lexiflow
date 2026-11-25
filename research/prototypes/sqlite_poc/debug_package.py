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
from release_package import export_package, record_rights
from review_queue import IDENTITY, apply, tasks

FIXTURES = [
    dict(word='zeta-demo', pos='noun', forms=[dict(form='zeta-demos')], senses=[
        dict(glosses=['демо-объект'], examples=[dict(text='[Fixture A]', translation='[Пример A]')]),
        dict(glosses=['демо-символ']),
    ]),
    dict(word='zeta-demo', pos='verb', senses=[dict(glosses=['демо-действие'])]),
    dict(word='zeta demo phrase', pos='phrase', senses=[dict(glosses=['демо-выражение'])]),
]


def make(output):
    with TemporaryDirectory() as temp, closing(sqlite3.connect(':memory:')) as src, closing(sqlite3.connect(':memory:')) as registry:
        source = Path(temp)/'artificial.jsonl'
        source.write_text('\n'.join(json.dumps(dict(record,lang_code='en')) for record in FIXTURES)+'\n', encoding='utf-8')
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
        for locator, in src.execute('SELECT locator FROM evidence').fetchall():
            record_rights(src,sha,locator,source_url='https://example.invalid/lexiflow-fixtures',
                          terms_url='https://example.invalid/lexiflow-fixtures',
                          license_id='artificial-fixture', attribution='LexiFlow synthetic fixtures',
                          modification='synthetic',decision='cleared',reason='technical fixture only',reviewer='fixture')
        registry.executescript(identity.SCHEMA)
        for kind in ('entry','sense'):
            for local_id, locator in src.execute(f'SELECT t.id,e.locator FROM {kind} t JOIN evidence e ON e.id=t.evidence_id'):
                stable = str(uuid5(NAMESPACE_URL,'lexiflow-debug-v1/'+locator))
                identity.bind(registry,kind,sha,local_id,stable,reason='synthetic fixture')
        result = export_package(src,registry,output,'synthetic-debug-v1')
        shutil.copyfile(Path(__file__).with_name('normalization_vectors.json'), output/'normalization_vectors.json')
        return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    args = parser.parse_args()
    print(json.dumps(make(args.output)['counts']))
