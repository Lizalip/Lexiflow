"""Artificial regressions for the pilot; source excerpts remain local."""
import csv
import json
import sqlite3
import unittest
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory

from candidate_rank import triage
from kaikki import import_sample
from prototype import create_database
from release_package import readiness, validate_package
from review_queue import FIELDS, EDITOR_FIELDS, apply, export_triage, triage_rows, validate
from scale_fixture import make
from tei_candidates import prefix_candidates


def record(word, gloss, **sense):
    return dict(word=word,lang_code='en',pos='noun',senses=[dict(glosses=[gloss],**sense)])


class PilotTests(unittest.TestCase):
    def test_ranked_deduplicates_normalized_spellings_preserves_locators_and_excludes(self):
        with TemporaryDirectory() as tmp, closing(sqlite3.connect(':memory:')) as db:
            path = Path(tmp)/'source.jsonl'
            fixture = [
                record('Artificial','объект'), record('ARTIFICIAL','предмет',examples=[dict(text='artificial')]),
                record('form','форма объекта',tags=['form-of']),record('mixed','объект; определение'),
                record('broken','объект',examples='bad'),record('alternative','предмет'),
            ]
            path.write_text('\n'.join(json.dumps(item) for item in fixture),encoding='utf-8')
            create_database(db)
            report = import_sample(db,path,method='ranked',limit=2,seed=42)
            locators = [row[0] for row in db.execute('SELECT ev.locator FROM entry e JOIN evidence ev ON ev.id=e.evidence_id ORDER BY ev.id')]
            self.assertEqual(locators,['line/2','line/6'])
            self.assertEqual(report['selection']['selection_ambiguity_only'],1)
            self.assertEqual(report['selection']['selection_no_content_or_form_only'],1)
            self.assertEqual(report['selection']['selection_malformed_nested'],1)
            self.assertEqual(report['verified_lexical_translations'],0)
            self.assertEqual(readiness(db)['approved_with_rights'],0)
            self.assertEqual(db.execute("SELECT count(*) FROM evidence WHERE rights!='pending'").fetchone()[0],0)

    def test_triage_is_explicitly_unapproved_and_exports_strata_reproducibly(self):
        self.assertEqual(triage('искусственный объект')[1:3],('standard','искусственный объект'))
        self.assertEqual(triage('объект (значение)')[1],'specialist')
        self.assertEqual(triage('объект; описание')[1],'specialist')
        self.assertEqual(triage('форма объекта')[1],'exclude')
        with TemporaryDirectory() as tmp, closing(sqlite3.connect(':memory:')) as db:
            path = Path(tmp)
            raw = path/'source.jsonl'
            raw.write_text('\n'.join(json.dumps(r) for r in [
                record('artificial one','объект'), record('artificial two','объект; дефиниция'),
                record('=artificial','форма объекта',tags=['form-of']),
            ]),encoding='utf-8')
            create_database(db)
            import_sample(db,raw)
            db.row_factory = sqlite3.Row
            candidates = list(triage_rows(db,'standard'))
            decision = {key:candidates[0][key] for key in FIELDS | EDITOR_FIELDS}
            self.assertEqual(validate(db,[decision]),1)
            with self.assertRaises(ValueError):
                apply(db,[decision])
            self.assertEqual(export_triage(db,path/'a.jsonl',limit=2),
                             {'specialist':1,'standard':1})
            export_triage(db,path/'b.jsonl',limit=2)
            self.assertEqual((path/'a.jsonl').read_bytes(),(path/'b.jsonl').read_bytes())
            export_triage(db,path/'c.csv',priority='exclude')
            with (path/'c.csv').open(encoding='utf-8-sig',newline='') as stream:
                self.assertEqual(next(csv.DictReader(stream))['headword'],"'=artificial")

    def test_tei_preserves_senses_languages_and_partial_record_boundary(self):
        raw = b"""<entry xml:id='fixture'><form><orth>artificial</orth></form>
            <sense><def>technical definition</def><cit type='trans' xml:lang='ru'><quote>candidate</quote></cit>
            <cit type='trans'><quote>unspecified</quote></cit><cit type='trans' xml:lang='fr'><quote>ignore</quote></cit>
            </sense><sense><cit type='trans'><quote xml:lang='ru'>second</quote></cit></sense></entry><entry>"""
        first, = prefix_candidates(raw)
        self.assertEqual(first['source_id'],'fixture')
        self.assertEqual(len(first['senses']),2)
        self.assertEqual(first['senses'][1]['translations'][0]['locator'],'bytes/0/sense/1/cit/0/quote/0')
        self.assertEqual(first['senses'][0]['translations'][0]['rights'],'pending')
        self.assertEqual(len(first['unspecified_target']),1)
        with self.assertRaises(ValueError):
            list(prefix_candidates(b' '*65_537))

    def test_scaled_fixture_keeps_release_gate_and_synthetic_identity(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp)/'fixture'
            result = make(path,12)
            self.assertEqual(result['corpus_version'],'synthetic-scale-12')
            self.assertEqual(result['counts']['translation'],12)
            self.assertEqual(validate_package(path),result)
            with self.assertRaises(ValueError):
                make(Path(tmp)/'invalid',20_001)
