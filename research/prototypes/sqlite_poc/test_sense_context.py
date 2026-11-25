import json
import sqlite3
import unittest
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory

from debug_package import make
from prototype import create_database
from release_package import validate_package
from sense_context import apply_decisions, export_context


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
