import json
import sqlite3
import unittest
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory

from corpus_enrichment import SOURCE
from prepare_release_asset import prepare
from prototype import create_database
from release_package import checksum, expand_attributions, export_package, validate_package
from research_cefr_package import add_levels
from research_package import SNAPSHOT, VERSION, build, validate_research_pair


class ResearchPackageTest(unittest.TestCase):
    def test_research_export_keeps_source_senses_and_cannot_enter_release(self):
        with TemporaryDirectory() as temp, closing(sqlite3.connect(':memory:')) as src:
            root = Path(temp)
            create_database(src)
            with src:
                src.execute('INSERT INTO source VALUES (1,?)',(SOURCE,))
                src.execute('INSERT INTO release VALUES (1,1,?,?)',(SNAPSHOT,VERSION))
                src.executemany('INSERT INTO evidence(id,release_id,locator) VALUES (?,1,?)',[
                    (1,'entry/0'),(2,'entry/0/sense/0'),(3,'entry/0/sense/0/cit/0'),
                    (4,'entry/0/sense/1'),(5,'entry/0/sense/1/cit/0'),
                    (6,'entry/0/sense/0/sense/0'),(7,'entry/0/sense/0/sense/0/def/0'),
                ])
                src.execute("INSERT INTO entry VALUES ('e','en','examplar','examplar','n',1)")
                src.executemany("INSERT INTO sense VALUES (?,'e',?,?,?)",[
                    ('s1',1,2,'{}'),('s2',2,4,'{}'),('child',3,6,'{"parent_sense_id":"s1"}'),
                ])
                src.execute("INSERT INTO gloss VALUES (1,'s1','ru','искусственный смысл','source_gloss',1,3)")
                src.execute("INSERT INTO gloss VALUES (2,'s2','ru','другой смысл','source_gloss',1,5)")
                src.execute("INSERT INTO gloss VALUES (3,'child','en','Fixture definition','definition',1,7)")
            before = list(src.iterdump())
            path = root/'research'
            result = build(src,{'examplar':1},path,1)
            self.assertEqual(result['counts']['sense'],2)
            self.assertEqual(result['counts']['translation'],2)
            self.assertEqual(validate_research_pair(path)['counts']['sense'],2)
            manifest = path/'manifest.json'
            record = json.loads(manifest.read_text(encoding='utf-8'))
            pooled_path = root/'pooled'
            build(src,{'examplar':1},pooled_path,1,shared_modifications=True)
            pooled = validate_research_pair(pooled_path)
            self.assertEqual(pooled['package_format'],3)
            self.assertEqual(pooled['database_sha256'],record['database_sha256'])
            self.assertEqual(expand_attributions(record['policies'],record['attributions']),
                expand_attributions(pooled['policies'],pooled['attributions'],pooled['modifications']))
            with closing(sqlite3.connect(pooled_path/'dictionary.sqlite')) as package:
                stats = add_levels(json.loads(json.dumps(pooled)),package,[
                    (2,'examplar','noun','A1'),(3,'examplar','noun','B2'),(4,'examplar','verb','A2')])
                self.assertEqual(stats,{'articles':1,'links':2,'articles_with_multiple_levels':1})
                enriched = json.loads(json.dumps(pooled))
                add_levels(enriched,package,[(2,'examplar','noun','A1')])
            pooled_file = pooled_path/'manifest.json'
            pooled_file.write_text(json.dumps(enriched),encoding='utf-8')
            self.assertEqual(len(validate_research_pair(pooled_path)['entry_cefr']['entries']),1)
            bad_label = next(iter(enriched['entry_cefr']['entries'].values()))[0]
            bad_label['level']='B1'
            pooled_file.write_text(json.dumps(enriched),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'invalid CEFR provenance'):
                validate_package(pooled_path)
            pooled_file.write_text(json.dumps(pooled),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'research content'):
                prepare(pooled_path,root/'assets-pooled',checksum(pooled_file))
            first = next(iter(pooled['attributions']))
            for changes,error in (
                    ({'modifications':None},'requires research'),
                    ({'modifications':[]},'invalid modification table'),
                    ({'modifications':pooled['modifications']*2},'invalid modification table'),
                    ({'modifications':['research-local-v1: tampered']},'invalid attribution'),
                    ({'attributions':{**pooled['attributions'],first:[0,'entry/0',42]}},'unknown modification'),
                    ({'policies':[{**pooled['policies'][0],'attribution':'tampered'}]},'invalid attribution'),
                    ({'package_format':4},'unsupported dictionary package')):
                pooled_file.write_text(json.dumps({**pooled,**changes}),encoding='utf-8')
                with self.assertRaisesRegex(ValueError,error):
                    validate_package(pooled_path)
            rendered = json.dumps(pooled)
            pooled_file.write_text(rendered.replace('"modifications":','"modifications": [], "modifications":',1),
                                   encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'duplicate manifest'):
                validate_package(pooled_path)
            notes, = record['source_entry_notes']['entries'].values()
            self.assertEqual(len(notes),1)
            with closing(sqlite3.connect(path/'dictionary.sqlite')) as package:
                self.assertEqual(package.execute("SELECT count(*) FROM lexical_metadata WHERE kind='definition'").fetchone()[0],0)
            self.assertEqual(list(src.iterdump()),before)
            with self.assertRaisesRegex(ValueError,'no eligible entries'):
                export_package(src,None,root/'production','release')
            with self.assertRaisesRegex(ValueError,'research content'):
                prepare(path,root/'assets',checksum(manifest))
            self.assertNotIn('sense_context',record)
            record.pop('distribution_policy')
            record['corpus_version']='renamed'
            manifest.write_text(json.dumps(record),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'research package markers'):
                validate_package(path)

    def test_wrong_snapshot_is_rejected_without_output(self):
        with TemporaryDirectory() as temp, closing(sqlite3.connect(':memory:')) as src:
            create_database(src)
            src.execute('INSERT INTO source VALUES (1,?)',(SOURCE,))
            src.execute('INSERT INTO release VALUES (1,1,?,?)',('a'*64,VERSION))
            path = Path(temp)/'wrong'
            with self.assertRaisesRegex(ValueError,'pinned FreeDict snapshot'):
                build(src,{},path,1)
            self.assertFalse(path.exists())
