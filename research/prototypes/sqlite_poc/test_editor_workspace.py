import tempfile
import unittest
import sqlite3
import json
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from editor_workspace import attach_assessments, diagnostic_alignment, write_html
from prototype import create_database


class WorkspaceTest(unittest.TestCase):
    def test_preliminary_assessment_requires_exact_unique_source_keys(self):
        identity = dict(entry_id='e',sense_id='s',snapshot_sha256='a'*64,locator='entry/1/sense/0')
        decision = dict(identity,category='clarification',reason='fixture rationale',suggestion='fixture')
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)/'assessment.jsonl'
            row = dict(identity,status='pending',translation_rights='pending')
            path.write_text(json.dumps(decision)+'\n'+json.dumps(dict(decision,locator='wrong')),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'unknown or repeated'):
                attach_assessments([row],path)
            self.assertNotIn('assessment_category',row)
            path.write_text((json.dumps(decision)+'\n')*2,encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'unknown or repeated'):
                attach_assessments([row],path)
            path.write_text(json.dumps(decision),encoding='utf-8')
            self.assertEqual({'clarification':1},attach_assessments([row],path))
            self.assertEqual('fixture',row['preliminary_suggestion'])
            self.assertEqual('pending',row['status'])
            self.assertEqual('pending',row['translation_rights'])

    def test_imported_content_is_escaped_and_export_stays_proposed(self):
        attack = '</script><img src=x onerror=alert(1)>'
        row = dict(entry_id=attack,sense_id='s',snapshot_sha256='a'*64,locator=attack,
                   candidate=attack,headword=attack,pos=attack,source=attack,translation_rights='pending',
                   alternatives=2,priority='specialist',reason=attack,
                   context=[dict(text=attack,sense_id='child',locator=attack,rights='pending')])
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)/'review.html'
            write_html(out,[row])
            text = out.read_text(encoding='utf-8')
            self.assertNotIn(attack,text)
            self.assertIn('&lt;img',text)
            self.assertIn('\\u003c/script>',text)
            self.assertIn('decision:"proposed"',text)
            self.assertNotIn('decision:"approved"',text)
            self.assertNotIn('<script src=',text)

    def test_alignment_keeps_source_senses_separate_and_marks_ambiguous_pairs(self):
        with closing(sqlite3.connect(':memory:')) as free, closing(sqlite3.connect(':memory:')) as kaikki:
            for index,db in enumerate((free,kaikki),1):
                create_database(db)
                with db:
                    db.execute("INSERT INTO source VALUES (1,'fixture')")
                    db.execute("INSERT INTO release VALUES (1,1,?,'fixture')",(str(index)*64,))
                    db.executemany('INSERT INTO evidence(id,release_id,locator) VALUES (?,1,?)',[
                        (1,'entry'),(2,'sense/1'),(3,'gloss/1'),(4,'sense/2'),(5,'example'),
                    ])
                    db.execute("INSERT INTO entry VALUES ('e','en','sample','sample','noun',1)")
                    db.execute("INSERT INTO sense VALUES (?,'e',1,2,'[]')",(f'sense-{index}',))
                    db.execute("INSERT INTO gloss VALUES (1,?,'ru','пример','source_gloss',1,3)",(f'sense-{index}',))
                    if index == 2:
                        db.execute("INSERT INTO sense VALUES ('alternate','e',2,4,'[]')")
                        db.execute("INSERT INTO example VALUES (1,'alternate','en','fixture',NULL,5)")
            with patch('editor_workspace.diagnostic_spellings',return_value={'sample'}):
                rows, counts = diagnostic_alignment(free,kaikki)
                self.assertEqual((rows,counts),diagnostic_alignment(free,kaikki))
            self.assertEqual(len(rows),2)
            self.assertTrue(all(row['status']=='pending' and row['other_same_pos']==2 for row in rows))
            self.assertEqual({row['kaikki_sense'] for row in rows},{'sense-2','alternate'})
            self.assertTrue(all(row['free_snapshot'] != row['kaikki_snapshot'] for row in rows))
