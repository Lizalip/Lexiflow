"""Synthetic fixtures only. No genuine lexical content or rights claims."""
import json
import sqlite3
from contextlib import closing
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from prototype import create_database, integrity, normalize, prefix_end, query_plan, search, snapshot_id
from kaikki import import_sample


class SearchTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        create_database(self.db)
        self.db.execute("INSERT INTO source VALUES (1,'synthetic-fixture')")
        self.db.execute("INSERT INTO release VALUES (1,1,?,'fixture')",('a'*64,))
        self.seq = 0
        for eid, spelling, pos in [('e1','xlex','noun'),('e2','xlex','verb'),('e3','xlex  tail','noun'),('e4','xother','noun')]:
            self.db.execute('INSERT INTO entry VALUES (?,?,?,?,?,?)',(eid,'en',spelling,normalize(spelling),pos,self.evidence()))
        for sid,eid,i in [('s1','e1',1),('s2','e1',2),('s3','e2',1),('s4','e3',1),('s5','e4',1)]:
            self.db.execute('INSERT INTO sense VALUES (?,?,?,?,?)',(sid,eid,i,self.evidence('duplicate' if sid in ('s1','s2') else None),'[]'))
        for sid,word,rights in [('s1','Ёж-тест','cleared'),('s2','ёж-тест','cleared'),('s3','Ёж-тест хвост','cleared'),('s4','Ёж-тест','pending')]:
            self.db.execute('INSERT INTO translation(sense_id,lang,wording,search_key,evidence_id) VALUES (?,?,?,?,?)',(sid,'ru',word,normalize(word),self.evidence(rights=rights)))
        self.db.execute('INSERT INTO gloss(sense_id,lang,body,role,position,evidence_id) VALUES (?,?,?,?,?,?)',('s5','ru','неиндексируемая','source_gloss',1,self.evidence()))
        self.db.commit()

    def evidence(self, external=None, rights='cleared'):
        self.seq += 1
        return self.db.execute('INSERT INTO evidence(release_id,locator,external_id,rights,review_note) VALUES (1,?,?,?,?)',(f'fixture/{self.seq}',external,rights,'synthetic test only' if rights=='cleared' else None)).lastrowid

    def tearDown(self): self.db.close()

    def test_integrity(self): integrity(self.db)
    def test_exact_multiple_parts_of_speech_and_senses(self):
        self.assertEqual({x['sense_id'] for x in search(self.db,'en-ru','XLEX',exact=True)},{'s1','s2','s3'})
    def test_prefix_order_and_limit(self):
        rows=search(self.db,'en-ru',' XLEX ')
        self.assertEqual([x['sense_id'] for x in rows],['s1','s2','s3','s4'])
        self.assertEqual(len(search(self.db,'en-ru','xlex',limit=2)),2)
    def test_reverse_excludes_pending(self):
        self.assertEqual([x['sense_id'] for x in search(self.db,'ru-en','ЁЖ-ТЕСТ')],['s1','s2','s3'])
        self.assertEqual({x['sense_id'] for x in search(self.db,'ru-en','ёж-тест',exact=True)},{'s1','s2'})
    def test_reverse_excludes_pending_parent(self):
        self.db.execute("UPDATE evidence SET rights='pending' WHERE id=(SELECT evidence_id FROM entry WHERE id='e1')")
        self.assertEqual([x['sense_id'] for x in search(self.db,'ru-en','ёж-тест')],['s3'])
    def test_gloss_is_not_translation(self): self.assertEqual(search(self.db,'ru-en','неиндексируемая'),[])
    def test_duplicate_external_id(self):
        self.assertEqual(self.db.execute("SELECT count(*) FROM evidence WHERE external_id='duplicate'").fetchone()[0],2)
    def test_foreign_key(self):
        with self.assertRaises(sqlite3.IntegrityError): self.db.execute('INSERT INTO sense VALUES (?,?,?,?,?)',('bad','missing',1,self.evidence(),'[]'))
    def test_clear_requires_review_note(self):
        with self.assertRaises(sqlite3.IntegrityError): self.db.execute("INSERT INTO evidence(release_id,locator,rights) VALUES (1,'bad','cleared')")
    def test_empty_and_parameterized(self):
        self.assertEqual(search(self.db,'en-ru','  '),[])
        self.assertEqual(search(self.db,'en-ru',"xlex' OR 1=1 --"),[])
        self.assertEqual(search(self.db,'en-ru','%'),[])
    def test_index_usage(self):
        self.assertTrue(any('entry_search' in p and 'SEARCH' in p for p in query_plan(self.db,'en-ru','xlex')))
        self.assertTrue(any('translation_search' in p and 'SEARCH' in p for p in query_plan(self.db,'ru-en','ёж')))
    def test_incomplete_entry(self):
        self.db.execute('INSERT INTO entry VALUES (?,?,?,?,?,?)',('e5','en','xmissing','xmissing',None,self.evidence(rights='pending')))
        r=search(self.db,'en-ru','xmissing')[0]; self.assertIsNone(r['sense_id']); self.assertEqual(r['rights'],'pending')


class NormalizationTests(unittest.TestCase):
    def test_vectors(self):
        vectors=json.loads(Path(__file__).with_name('normalization_vectors.json').read_text(encoding='utf-8'))
        for v in vectors['vectors']:
            with self.subTest(v=v): self.assertEqual(normalize(v['input']),v['expected'])
    def test_prefix_boundaries(self):
        self.assertEqual(prefix_end('a\U0010ffff'),'b')
        self.assertIsNone(prefix_end('\U0010ffff'))
        self.assertEqual(prefix_end('\ud7ff'),'\ue000')
    def test_invalid_scalar(self):
        for s in ('\ud800','a\x00b'):
            with self.assertRaises(ValueError): normalize(s)
    def test_snapshot_identity(self):
        self.assertEqual(snapshot_id('a','b','c'),snapshot_id('a','b','c'))
        self.assertNotEqual(snapshot_id('a','b','c'),snapshot_id('a','b','d'))
        self.assertNotEqual(snapshot_id('a','b','c'),snapshot_id('a','e','c'))


class ImportTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.path=Path(self.tmp.name)/'synthetic.jsonl'
        records=[{'word':'xlex','lang_code':'en','pos':'noun','senses':[{'id':'same','glosses':['тест А'],'examples':[{'text':'fixture text','translation':'тест','ref':'synthetic'}]},{'id':'same','glosses':['тест Б'],'raw_tags':['fixture'] }], 'sounds':[{'ipa':'/x/','audio':'fixture.ogg'}], 'forms':[{'form':'xlexs','tags':['plural']}]}, {'word':'xlex','lang_code':'en','pos':'verb','senses':[{'glosses':[]},None]}, {'word':'xother','lang_code':'en','senses':'bad'}, {'word':'wrong','lang_code':'de'}]
        self.path.write_bytes(('\n'.join(json.dumps(r,ensure_ascii=False) for r in records)+'\n{bad\n').encode()+b'\xff\n'+b'{"word":"x","lang_code":"en","senses":[NaN]}\n')
        self.db=sqlite3.connect(':memory:'); self.addCleanup(self.db.close); create_database(self.db)
    def test_stream_counts_and_pending_rights(self):
        r=import_sample(self.db,self.path,limit=7)
        self.assertEqual((r['lines_read'],r['accepted_records'],r['rejected_records'],r['senses']),(7,3,4,3))
        self.assertEqual(r['unique_spellings'],2)
        self.assertEqual(r['duplicate_source_id_excess'],1)
        self.assertEqual(r['missing_sense_ids'],1)
        self.assertEqual(r['senses_with_gloss'],2)
        self.assertEqual(r['senses_with_translated_example'],1)
        self.assertEqual(r['records_with_ipa'],1)
        self.assertEqual(r['records_with_audio'],1)
        self.assertEqual(r['records_with_unresolved_rights'],3)
        self.assertEqual(self.db.execute("SELECT count(*) FROM evidence WHERE rights!='pending'").fetchone()[0],0)
        self.assertEqual(self.db.execute('SELECT count(*) FROM translation').fetchone()[0],0)
        self.assertEqual(search(self.db,'ru-en','тест'),[])
        integrity(self.db)
    def test_limit_and_repeat_fresh(self):
        a=import_sample(self.db,self.path,limit=1)
        self.assertEqual(a['lines_read'],1)
        with self.assertRaises(ValueError): import_sample(self.db,self.path,limit=1)
        with sqlite3.connect(':memory:') as other:
            create_database(other); b=import_sample(other,self.path,limit=1)
            self.assertEqual(a,b); self.assertEqual(list(self.db.iterdump()),list(other.iterdump()))
    def test_bound(self):
        with self.assertRaises(ValueError): import_sample(self.db,self.path,limit=1001)
    def test_example_lineage(self):
        import_sample(self.db,self.path,limit=1)
        row=self.db.execute('SELECT s.position, e.body, t.body FROM example_translation t JOIN example e ON e.id=t.example_id JOIN sense s ON s.id=e.sense_id').fetchone()
        self.assertEqual(row,(1,'fixture text','тест'))
        self.assertEqual(self.db.execute('SELECT count(*) FROM form').fetchone()[0],1)
        self.assertEqual(self.db.execute('SELECT count(*) FROM pronunciation').fetchone()[0],1)
    def test_cli_creates_new_file_and_refuses_overwrite(self):
        output=Path(self.tmp.name)/'result.sqlite'
        cmd=[sys.executable,str(Path(__file__).with_name('run_import.py')),str(self.path),str(output),'--limit','1']
        result=subprocess.run(cmd,capture_output=True,text=True,check=True)
        self.assertEqual(json.loads(result.stdout)['accepted_records'],1)
        with closing(sqlite3.connect(output)) as exported:
            integrity(exported)
            self.assertEqual(exported.execute('SELECT count(*) FROM entry').fetchone()[0],1)
        original=output.read_bytes()
        result=subprocess.run(cmd,capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(output.read_bytes(),original)
    def test_cli_failure_does_not_leave_database(self):
        output=Path(self.tmp.name)/'failed.sqlite'
        cmd=[sys.executable,str(Path(__file__).with_name('run_import.py')),str(self.path.with_name('missing.jsonl')),str(output)]
        result=subprocess.run(cmd,capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertFalse(output.exists())
    def test_bad_nested_types(self):
        p=Path(self.tmp.name)/'nested.jsonl'; p.write_text(json.dumps({'word':'x','lang_code':'en','senses':[{'glosses':[None,3,'  '],'examples':[{},'bad'],'tags':'bad'}],'sounds':[3],'forms':[{}]})+'\n',encoding='utf-8')
        r=import_sample(self.db,p,limit=1)
        self.assertEqual(r['accepted_records'],1); self.assertEqual(r['senses_with_gloss'],0); self.assertGreaterEqual(r['issues_review'],7)

if __name__=='__main__': unittest.main()
