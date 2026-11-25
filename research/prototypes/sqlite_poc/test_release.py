"""Artificial fixtures for the review, identity, search and package contracts."""
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from uuid import UUID

import identity
from kaikki import import_sample
from prototype import create_database, normalize, query_plan, release_lookup, research_lookup
from release_package import checksum, export_package, record_rights, validate_package
from review_queue import IDENTITY, apply, progress, suggest, tasks, validate


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name)
        self.staging = sqlite3.connect(':memory:')
        self.addCleanup(self.staging.close)
        create_database(self.staging)
        raw = self.path/'fixture.jsonl'
        raw.write_text(json.dumps(dict(word='artificial',lang_code='en',pos='noun',
            forms=[dict(form='artificial-form')], senses=[
                dict(id='duplicate',glosses=['artificial gloss'],
                     examples=[dict(text='artificial context',translation='artificial paired context')]),
                dict(id='duplicate',glosses=['artificial extra']),
            ])),encoding='utf-8')
        self.sha = import_sample(self.staging,raw)['snapshot_sha256']
        self.staging.row_factory = sqlite3.Row
        self.rows = list(tasks(self.staging))
        self.staging.row_factory = None
        self.approved = [dict(**{key:row[key] for key in IDENTITY}, classification='candidate_translation',
                              proposed_translation='artificial translation', decision='approved',
                              reviewer='fixture', method='human', reason='artificial test only')
                         for row in self.rows]
        self.registry = sqlite3.connect(':memory:')
        self.addCleanup(self.registry.close)
        self.registry.executescript(identity.SCHEMA)
        refs = [('entry',self.rows[0]['entry_id'])] + [('sense',row['sense_id']) for row in self.rows]
        identity.load_registry(self.registry, [
            dict(kind=kind,snapshot=self.sha,local_id=ref,persistent_id=str(UUID(int=n)),reason='artificial')
            for n,(kind,ref) in enumerate(refs,1)
        ])

    def rights(self, locator, decision='cleared'):
        record_rights(self.staging,self.sha,locator,source_url='https://example.invalid/fixture',
                      license_id='fixture',terms_url='https://example.invalid/terms',
                      attribution='artificial only',modification='fixture only',
                      decision=decision,reason='artificial test',reviewer='fixture')

    def clear_core(self):
        for locator in ('line/1','line/1/senses/0',self.rows[0]['locator']):
            self.rights(locator)
        translation = self.staging.execute("SELECT locator FROM evidence WHERE locator LIKE '%/editor/%' ORDER BY id").fetchone()[0]
        self.rights(translation)

    def test_tasks_and_approval_history(self):
        self.assertEqual(self.rows[0]['headword'],'artificial')
        self.assertEqual(self.rows[0]['examples'],[['artificial context','artificial paired context']])
        proposal = suggest(self.rows[0])
        self.assertEqual(validate(self.staging,[proposal]),1)
        with self.assertRaisesRegex(ValueError,'cannot be applied'):
            apply(self.staging,[proposal])
        with self.assertRaisesRegex(ValueError,'human'):
            validate(self.staging,[proposal | dict(decision='approved')])
        with self.assertRaisesRegex(ValueError,'mismatch'):
            apply(self.staging,[self.approved[0] | dict(snapshot_sha256='0'*64)])
        self.assertEqual(apply(self.staging,self.approved),2)
        self.assertEqual(apply(self.staging,self.approved),0)
        with self.assertRaisesRegex(ValueError,'conflicting'):
            apply(self.staging,[self.approved[0] | dict(reason='conflict')])
        self.assertEqual(progress(self.staging)['approved'],2)
        self.assertEqual(self.staging.execute('SELECT body FROM gloss ORDER BY id').fetchone()[0],'artificial gloss')
        self.assertEqual(release_lookup(self.staging,'ru-en','artificial'),[])

    def test_failed_batch_rolls_back(self):
        with self.assertRaises(ValueError):
            apply(self.staging,[self.approved[0], self.approved[1] | dict(locator='stale')])
        self.assertEqual(progress(self.staging)['approved'],0)
        self.assertEqual(self.staging.execute('SELECT count(*) FROM translation').fetchone()[0],0)

    def test_export_refuses_empty_and_unreviewed_packages(self):
        out = self.path/'refused'
        with self.assertRaisesRegex(ValueError,'no eligible'):
            export_package(self.staging,self.registry,out,'fixture')
        apply(self.staging,self.approved)
        with self.assertRaisesRegex(ValueError,'no eligible'):
            export_package(self.staging,self.registry,out,'fixture')
        self.assertFalse(out.exists())

    def test_export_filters_parents_and_excluded_elements(self):
        apply(self.staging,self.approved)
        self.clear_core()
        self.rights('line/1/senses/0/examples/0/translation')
        self.rights('line/1/forms/0','excluded')
        out = self.path/'filtered'
        result = export_package(self.staging,self.registry,out,'fixture')
        self.assertEqual(result['counts'],dict(entry=1,sense=1,translation=1,form=0,example=0,example_translation=0))
        self.rights('line/1/senses/0/examples/0')
        result = export_package(self.staging,self.registry,self.path/'with_example','fixture')
        self.assertEqual(result['counts']['example_translation'],1)

    def test_export_determinism_and_checksum(self):
        apply(self.staging,self.approved)
        self.clear_core()
        one,two = self.path/'one',self.path/'two'
        self.assertEqual(export_package(self.staging,self.registry,one,'fixture'),
                         export_package(self.staging,self.registry,two,'fixture'))
        with self.assertRaises(FileExistsError):
            export_package(self.staging,self.registry,one,'fixture')
        with (one/'dictionary.sqlite').open('ab') as stream:
            stream.write(b'changed')
        with self.assertRaisesRegex(ValueError,'checksum'):
            validate_package(one)
        manifest = two/'manifest.json'
        data = json.loads(manifest.read_text())
        data.pop('attributions')
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError,'missing attribution'):
            validate_package(two)

    def test_rights_decision_requires_locator_and_evidence(self):
        apply(self.staging,self.approved)
        with self.assertRaisesRegex(ValueError,'missing or ambiguous'):
            self.rights('missing')
        self.rights('line/1')
        self.rights('line/1')
        with self.assertRaisesRegex(ValueError,'conflicting'):
            self.rights('line/1','excluded')

    def test_manifest_with_matching_checksum_still_rejects_missing_index(self):
        apply(self.staging,self.approved)
        self.clear_core()
        package = self.path/'bad_schema'
        export_package(self.staging,self.registry,package,'fixture')
        database = package/'dictionary.sqlite'
        with closing(sqlite3.connect(database)) as conn:
            conn.execute('DROP INDEX form_search')
        manifest = package/'manifest.json'
        data = json.loads(manifest.read_text())
        data['database_sha256'] = checksum(database)
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError,'schema or indexes'):
            validate_package(package)

    def test_form_lookup_uses_index_and_filters_rights(self):
        apply(self.staging,self.approved)
        self.clear_core()
        self.assertEqual(len(research_lookup(self.staging,'en-ru','artificial-f')),2)
        self.assertEqual(release_lookup(self.staging,'en-ru','artificial-f'),[])
        self.rights('line/1/forms/0')
        self.assertEqual(len(release_lookup(self.staging,'en-ru','artificial-f')),1)
        self.assertTrue(any('SEARCH f USING' in step and 'form_search' in step
                            for step in query_plan(self.staging,'en-ru','artificial-f')))
        with self.assertRaises(ValueError):
            research_lookup(self.staging,'en-ru','a'*257)

    def test_reverse_groups_senses_before_limit(self):
        apply(self.staging,self.approved)
        self.clear_core()
        self.rights('line/1/senses/1')
        self.staging.execute("UPDATE evidence SET rights='cleared', review_note='artificial' WHERE locator LIKE '%/editor/%'")
        duplicate = self.staging.execute("""
            INSERT INTO evidence(release_id,locator,rights,review_note) VALUES (1,'fixture/duplicate','cleared','artificial')
        """).lastrowid
        self.staging.execute("INSERT INTO translation(sense_id,lang,wording,search_key,evidence_id) VALUES (?,'ru','artificial','artificial',?)",
                             (self.rows[0]['sense_id'],duplicate))
        found = release_lookup(self.staging,'ru-en','artificial',limit=2)
        self.assertEqual(len({hit['sense_id'] for hit in found}),2)
        self.assertEqual(found[0]['matched_translation'],'artificial')


class IdentityTests(unittest.TestCase):
    def test_shared_invalid_scalar_vectors(self):
        vectors = json.loads(Path(__file__).with_name('normalization_vectors.json').read_text(encoding='utf-8'))
        for codepoint in vectors['invalid_codepoints']:
            with self.subTest(codepoint=codepoint), self.assertRaises(ValueError):
                normalize(chr(codepoint))

    def test_reordered_duplicate_and_modified_candidates_require_explicit_binding(self):
        conn = sqlite3.connect(':memory:')
        self.addCleanup(conn.close)
        conn.executescript(identity.SCHEMA)
        snapshot,updated = '0'*64,'1'*64
        original = str(UUID(int=42))
        second = str(UUID(int=43))
        self.assertTrue(identity.bind(conn,'sense',snapshot,'row-1',original,reason='artificial'))
        identity.bind(conn,'sense',snapshot,'row-2',second,reason='artificial')
        old = [dict(local_id='row-1',source_id='dup',spelling='artificial',language='en',pos='noun',meaning='a'),
               dict(local_id='row-2',source_id='dup',spelling='artificial',language='en',pos='noun',meaning='b')]
        modified = old[0] | dict(local_id='new-row-2',meaning='edited')
        self.assertEqual(len(identity.candidates(old,modified)),2)
        with self.assertRaisesRegex(ValueError,'missing'):
            identity.lookup(conn,'sense',updated,'new-row-2')
        with self.assertRaisesRegex(ValueError,'explicit'):
            identity.bind(conn,'sense',updated,'new-row-2',original,reason='unconfirmed')
        self.assertTrue(identity.bind(conn,'sense',updated,'new-row-2',original,reason='reviewed revision',
                                      previous=(snapshot,'row-1')))
        self.assertFalse(identity.bind(conn,'sense',updated,'new-row-2',original,reason='reviewed revision',
                                       previous=(snapshot,'row-1')))
        with self.assertRaisesRegex(ValueError,'multiple objects'):
            identity.bind(conn,'sense',updated,'split-child',original,reason='split',
                          previous=(snapshot,'row-1'))
        with self.assertRaisesRegex(ValueError,'already'):
            identity.bind(conn,'sense',updated,'new-row-2',second,reason='merge another identity',
                          previous=(snapshot,'row-2'))
        entry_id = str(UUID(int=44))
        identity.bind(conn,'entry',snapshot,'row-3',entry_id,reason='artificial')
        identity.bind(conn,'entry',updated,'first-row',entry_id,reason='unchanged entry verified',
                      previous=(snapshot,'row-3'))
        self.assertEqual(identity.lookup(conn,'entry',updated,'first-row'),entry_id)
        self.assertEqual(identity.lookup(conn,'sense',snapshot,'row-2'),second)
        with self.assertRaises(ValueError):
            identity.lookup(conn,'sense',updated,'removed')
