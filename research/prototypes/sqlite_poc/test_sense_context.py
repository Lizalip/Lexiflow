import json
import sqlite3
import unittest
from contextlib import closing
from copy import deepcopy
from pathlib import Path
from tempfile import TemporaryDirectory

from debug_package import make
from prototype import create_database
from release_package import validate_package
from sense_context import apply_decisions, export_context, validate_context


class SenseContextTest(unittest.TestCase):
    def test_valid_package_keeps_stable_ids_and_rejects_bad_relationship(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            flat = make(root/'flat',2)
            nested = make(root/'nested',2,context=True)
            self.assertEqual(flat['database_sha256'],nested['database_sha256'])
            sense, = nested['sense_context']['senses'].values()
            self.assertEqual(sense['text'],'[Artificial nested context]')
            sense['owner'] = sense['definition']
            (root/'nested'/'manifest.json').write_text(json.dumps(nested),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'invalid source sense relationship'):
                validate_package(root/'nested')

    def test_context_rejects_wrong_source_snapshot_owner_and_definition(self):
        with closing(sqlite3.connect(':memory:')) as db:
            db.execute("CREATE TABLE sense(id TEXT, attribution_id TEXT)")
            db.execute("INSERT INTO sense VALUES ('fixture-sense','parent')")
            attrs = {
                'parent': dict(source='fixture', snapshot='a'*64, locator='entry/1/sense/0'),
                'owner': dict(source='fixture', snapshot='a'*64, locator='entry/1/sense/0/sense/0'),
                'definition': dict(source='fixture', snapshot='a'*64, locator='entry/1/sense/0/sense/0/def/0'),
            }
            extension = dict(version=1,senses={
                'fixture-sense': dict(relationship='direct-child',text='fixture definition',
                                      language='en',owner='owner',definition='definition')
            })
            validate_context(db,extension,attrs)
            for attr,field,value in (
                ('owner','source','different origin'),
                ('definition','snapshot','b'*64),
                ('owner','locator','entry/2/sense/0/sense/0'),
                ('definition','locator','entry/1/sense/0/sense/1/def/0'),
            ):
                with self.subTest(attr=attr,field=field):
                    wrong = deepcopy(attrs)
                    wrong[attr][field] = value
                    with self.assertRaisesRegex(ValueError,'invalid source sense relationship'):
                        validate_context(db,extension,wrong)
            for omitted in ('owner','definition'):
                with self.subTest(omitted=omitted):
                    with self.assertRaisesRegex(ValueError,'invalid sense context row'):
                        validate_context(db,extension,{k:v for k,v in attrs.items() if k != omitted})

    def test_missing_ambiguous_mismatched_and_denied_context(self):
        with closing(sqlite3.connect(':memory:')) as db:
            create_database(db)
            with db:
                db.execute("INSERT INTO source VALUES (1,'fixture')")
                db.execute("INSERT INTO release VALUES (1,1,?,'origin')",('a'*64,))
                db.execute("INSERT INTO release VALUES (2,1,?,'other')",('b'*64,))
                db.executemany('INSERT INTO evidence(id,release_id,locator) VALUES (?,?,?)',[
                    (1,1,'entry/1'),(2,1,'entry/1/sense/0'),(3,1,'entry/1/sense/0/sense/0'),
                    (4,1,'entry/1/sense/0/sense/0/def/0'),(5,1,'entry/1/sense/0/sense/1'),
                    (6,2,'entry/1/sense/0/sense/1/def/0'),
                    (7,1,'entry/1/sense/0/sense/1/def/1'),
                ])
                db.execute("INSERT INTO entry VALUES ('e','en','sample','sample','noun',1)")
                db.executemany("INSERT INTO sense VALUES (?,'e',?,?,?)",[
                    ('p',1,2,'{}'),('a',2,3,'{"parent_sense_id":"p"}'),('b',3,5,'{"parent_sense_id":"p"}'),
                ])
                db.executemany("INSERT INTO gloss VALUES (?,?, 'en','sample','definition',?,?)",[
                    (1,'a',1,4),(2,'b',1,6),(3,'b',2,7),
                ])
            decision = dict(parent_id='p',snapshot='a'*64,definition_locator='missing',reviewer='fixture',
                            reason='fixture only',decision='approved',method='human')
            self.assertEqual(export_context(db,{'p':'stable'},{},str),{})
            with self.assertRaisesRegex(ValueError,'missing or ambiguous'):
                apply_decisions(db,[decision])
            with self.assertRaisesRegex(ValueError,'invalid source'):
                apply_decisions(db,[dict(decision,definition_locator='entry/1/sense/0/sense/1/def/0')])
            approved = dict(decision,definition_locator='entry/1/sense/0/sense/0/def/0')
            alternative = dict(decision,definition_locator='entry/1/sense/0/sense/1/def/1')
            with self.assertRaisesRegex(ValueError,'conflicting'):
                apply_decisions(db,[approved,alternative])
            self.assertEqual(export_context(db,{'p':'stable'},{2:1,3:1,4:1},str),{})
            apply_decisions(db,[approved])
            self.assertEqual(export_context(db,{'p':'stable'},{2:1,3:1},str),{})
            self.assertEqual(export_context(db,{'p':'stable'},{2:1,3:1,4:1},str)['stable']['owner'],'3')
