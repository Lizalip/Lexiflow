import json
import sqlite3
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from research_capacity import capacity
from research_cefr import compare as compare_cefr, parse_rows
from research_corpus_audit import flags
from research_evaluation import HOLDOUT, REFERENCE, probe_set, reference_at_cutoff, summarize
from research_upgrade import differences
from research_reverse_aliases import stress_key
from research_tatoeba import pairs, token_phrase, usage_candidates


class ResearchAnalysisTest(unittest.TestCase):
    def test_usage_candidates_keep_phrases_and_attested_forms_and_defer_ambiguous_words(self):
        def bilingual(identifier,text):
            return dict(id=identifier,lang='eng',text=text,owner='fixture',license='CC BY 2.0 FR',
                        is_unapproved=False,translations=[
                dict(id=identifier+100,lang='rus',text='Тестовая строка.',owner='fixture',
                     license='CC BY 2.0 FR',is_unapproved=False,is_direct=True)])

        with sqlite3.connect(':memory:') as db:
            db.executescript("""
                CREATE TABLE entry(id,language,search_key,pos);
                CREATE TABLE form(id,entry_id,search_key);
                CREATE TABLE lexical_metadata(form_id,kind);
                INSERT INTO entry VALUES ('a','en','train','n'),('b','en','train','v'),
                    ('c','en','model','n'),('d','en','set the table','v');
                INSERT INTO form VALUES (1,'c','models');
                INSERT INTO lexical_metadata VALUES (1,'grammar');
            """)
            capture = {'data':[bilingual(1,'Set the table.'),bilingual(2,'These models train.'),
                               bilingual(3,'Train now'),bilingual(4,'The train.')] }
            stats,rows = usage_candidates(db,{'fixture':capture},{})
            self.assertEqual({(row['entry'],row['relationship']) for row in rows},{('c','form'),('d','lemma')})
            self.assertGreater(stats['ambiguous_tokens'],0)
            self.assertTrue(all(row['status']=='requires preliminary lexical review' for row in rows))

    def test_tatoeba_retains_only_direct_attributed_pairs(self):
        def row(identifier,language):
            return dict(id=identifier,lang=language,text='fixture',owner='fixture-author',
                        license='CC BY 2.0 FR',is_unapproved=False)
        source = dict(row(1,'eng'),translations=[
            dict(row(2,'rus'),is_direct=True),dict(row(3,'rus'),is_direct=False),
            dict(row(4,'rus'),is_direct=True,owner=None),
            dict(row(5,'rus'),is_direct=True,license='unknown'),
        ])
        (en,ru), = list(pairs(dict(data=[source])))
        self.assertEqual((en['id'],ru['id']),(1,2))
        self.assertIn('/2.0/fr/',ru['terms'])
        self.assertFalse(token_phrase(['ice'],['nice']))
        self.assertTrue(token_phrase(['two','words'],['these','two','words']))

    def test_reverse_alias_only_removes_russian_stress_marks(self):
        self.assertEqual(stress_key('  Ё́Ж   Кра́й  '),'ёж край')
        self.assertEqual(stress_key('café'),'café')
        self.assertNotEqual(stress_key('ёлка'),stress_key('елка'))
        self.assertNotEqual(stress_key('стои́т'),stress_key('стоить'))

    def test_cefr_requires_pos_and_keeps_ambiguous_lemma_levels(self):
        rows = list(parse_rows([('headword','pos','CEFR'),('word','noun','A1'),('word','noun','B2'),
                                ('word','verb','A2'),('verb','verb','B1')]))
        with sqlite3.connect(':memory:') as db:
            db.execute('CREATE TABLE entry(search_key,pos)')
            db.executemany('INSERT INTO entry VALUES (?,?)',[('word','n'),('verb','v')])
            report = compare_cefr(db,rows)
        self.assertEqual(report['multiple_levels_on_same_lemma_pos'],1)
        self.assertEqual(report['unambiguous_candidate_levels'],{'B1':1})
        self.assertEqual(report['unmatched_rows'],{'headword_only':1})
        self.assertEqual(report['assigned_sense_levels'],0)

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
