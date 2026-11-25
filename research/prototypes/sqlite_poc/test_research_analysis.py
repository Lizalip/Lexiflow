import json
import sqlite3
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from research_capacity import capacity
from research_corpus_audit import flags
from research_evaluation import HOLDOUT, REFERENCE, probe_set, reference_at_cutoff, summarize
from research_upgrade import differences


class ResearchAnalysisTest(unittest.TestCase):
    def test_heldout_has_no_overlap_and_covers_reference_probes(self):
        probes = dict((text,category) for category,text in probe_set())
        self.assertEqual(len(probes),264)
        self.assertEqual(set(probes.values()),set(HOLDOUT))
        self.assertLessEqual(REFERENCE.keys(),probes.keys())
        with sqlite3.connect(':memory:') as db:
            db.execute('CREATE TABLE translation(sense_id,search_key)')
            db.execute("INSERT INTO translation VALUES ('expected','хлеб')")
            lookup = [('e','wrong','bread','n'),('f','expected','bread','n')]
            self.assertTrue(reference_at_cutoff(db,'bread',False,lookup))
            self.assertFalse(reference_at_cutoff(db,'bread',False,lookup[:1]))
            self.assertIsNone(reference_at_cutoff(db,'garden',False,lookup))
        self.assertEqual(summarize([dict(category='test',exact=False,prefix=True,
                                        reference_top_10=False)])['total'],
                         dict(total=1,exact=0,prefix=1,reference_total=1,reference_found=0))

    def test_upgrade_differences_excludes_new_owners_and_detects_reassigned_rows(self):
        with sqlite3.connect(':memory:') as db:
            db.executescript("""
                CREATE TABLE entry(id TEXT PRIMARY KEY);
                CREATE TABLE sense(id, entry_id);
                ATTACH ':memory:' AS expanded;
                CREATE TABLE expanded.sense(id, entry_id);
                INSERT INTO entry VALUES ('original');
                INSERT INTO sense VALUES ('s1','original');
                INSERT INTO expanded.sense VALUES ('s1','original'),('s2','added');
            """)
            opts = ('id,entry_id','sense','entry_id','entry')
            self.assertEqual(differences(db,*opts),0)
            db.execute("UPDATE expanded.sense SET entry_id='added' WHERE id='s1'")
            self.assertEqual(differences(db,*opts),1)

    def test_capacity_estimate_preserves_canonical_values(self):
        payload = dict(counts={},policies=[{}],
                       attributions={'a':[0,'entry/1','same'], 'b':[0,'entry/2','same']})
        layout = dict(counts={},policies=[{}],modifications=['same'],
                      attributions={'a':[0,'entry/1',0], 'b':[0,'entry/2',0]})
        with TemporaryDirectory() as temp:
            root = Path(temp)
            dictionary,manifest = root/'dictionary.sqlite',root/'manifest.json'
            dictionary.write_bytes(b'sample')
            manifest.write_text(json.dumps(payload,separators=(',',':'))+'\n',encoding='ascii',newline='\n')
            summary = capacity(dictionary,manifest,payload)
            self.assertEqual(summary['projected_string_table_manifest_bytes'],
                             len(json.dumps(layout,separators=(',',':')))+1)
            self.assertEqual([row[2] for row in payload['attributions'].values()],['same','same'])
        self.assertIn('possible_malformed_record',flags('bad\ufffd'))
        self.assertIn('at_most_two_codepoints',flags('да'))
