"""Regression tests with artificial content and no real rights decisions."""
import io
import json
import sqlite3
import tempfile
import unittest
from collections import Counter
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from diagnostics import diagnostic_spellings, english_edition_shape
from kaikki import import_sample, sample_lines
from prototype import create_database, release_lookup, research_lookup
from review_queue import validate


class MilestoneTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        create_database(self.db)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.source = Path(directory.name) / 'artificial.jsonl'
        rows = [dict(word='artificial', lang_code='en', senses=[
            dict(id='duplicate', glosses=['artificial gloss']),
            dict(id='duplicate', tags=['form-of']),
        ])]
        self.source.write_bytes(b'invalid\n' + json.dumps(rows[0]).encode() + b'\n')

    def test_exact_preserves_source_line_and_snapshot_identity(self):
        report = import_sample(self.db, self.source, method='exact', targets={'artificial'})
        with closing(sqlite3.connect(':memory:')) as other:
            create_database(other)
            import_sample(other, self.source)
            self.assertEqual(self.db.execute('SELECT id FROM entry').fetchone(),
                             other.execute('SELECT id FROM entry').fetchone())
        self.assertEqual(self.db.execute('SELECT locator FROM evidence ORDER BY id').fetchone(), ('line/2',))
        self.assertEqual(report['scan_invalid_json'], 1)
        self.assertEqual(report['matched_target_spellings'], 1)
        self.assertEqual(report['duplicate_source_id_excess'], 1)
        self.assertEqual(report['form_reference_senses'], 1)
        self.assertEqual(report['pending_evidence'], 4)
        self.assertEqual(report['size_bytes'], self.source.stat().st_size)

    def test_seeded_reservoir_is_bounded_and_preserves_locators(self):
        def sample(seed):
            stream = io.BytesIO(b''.join(f'{index}\n'.encode() for index in range(1, 100)))
            stats = Counter()
            rows = list(sample_lines(stream, 8, 'random', seed, set(), stats))
            self.assertEqual(stats['lines_scanned'], 99)
            self.assertEqual(len(rows), 8)
            self.assertEqual(len(set(number for number, _ in rows)), 8)
            self.assertTrue(all(number == int(raw) for number, raw in rows))
            return rows
        self.assertEqual(sample(42), sample(42))
        self.assertNotEqual(sample(42), sample(43))

    def test_release_forward_requires_cleared_entry_and_sense(self):
        import_sample(self.db, self.source)
        self.assertEqual(len(research_lookup(self.db, 'en-ru', 'artificial')), 2)
        self.assertEqual(release_lookup(self.db, 'en-ru', 'artificial'), [])
        self.db.execute("UPDATE evidence SET rights='cleared', review_note='artificial' WHERE locator LIKE '%/senses/0'")
        self.assertEqual(release_lookup(self.db, 'en-ru', 'artificial'), [])
        self.db.execute("UPDATE evidence SET rights='cleared', review_note='artificial' WHERE locator='line/2'")
        hits = release_lookup(self.db, 'en-ru', 'artificial')
        self.assertEqual([row['position'] for row in hits], [1])
        self.assertNotIn('gloss', hits[0])

    def test_semantic_decision_validates_lineage_without_approving_rights(self):
        import_sample(self.db, self.source)
        entry, sense, digest, locator = self.db.execute('''
            SELECT s.entry_id, g.sense_id, r.sha256, ev.locator
            FROM gloss g JOIN sense s ON s.id=g.sense_id JOIN evidence ev ON ev.id=g.evidence_id
            JOIN release r ON r.id=ev.release_id
        ''').fetchone()
        decision = dict(entry_id=entry, sense_id=sense, snapshot_sha256=digest, locator=locator,
                        classification='candidate_translation', reason='artificial diagnostic')
        self.assertEqual(validate(self.db, [decision]), 1)
        for changed in [dict(entry_id='another'), dict(sense_id='another'), dict(classification='verified'),
                        dict(reason=''), dict(rights='cleared')]:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                validate(self.db, [decision | changed])
        with self.assertRaises(ValueError):
            validate(self.db, [decision, decision | dict(classification='definition')])
        self.assertEqual(self.db.execute("SELECT count(*) FROM evidence WHERE rights='pending'").fetchone()[0], 4)
        self.assertEqual(release_lookup(self.db, 'ru-en', 'artificial'), [])

    def test_overflowing_numeric_metadata_is_rejected_without_aborting(self):
        with self.source.open('ab') as stream:
            stream.write(b'{"word":"artificial","lang_code":"en","sounds":[{"raw":1e999}]}\n')
        report = import_sample(self.db, self.source)
        self.assertEqual((report['accepted_records'], report['rejected_records']), (1, 2))

    def test_import_failure_rolls_back_manifest_and_entries(self):
        with patch('kaikki.integrity', side_effect=RuntimeError('artificial failure')):
            with self.assertRaises(RuntimeError):
                import_sample(self.db, self.source)
        self.assertEqual(self.db.execute('SELECT count(*) FROM entry').fetchone()[0], 0)
        self.assertIsNone(self.db.execute("SELECT value FROM metadata WHERE key='import_manifest'").fetchone())

    def test_diagnostic_inputs_match_saved_notebook(self):
        targets = diagnostic_spellings()
        self.assertEqual(len(targets), 24)

    def test_english_edition_diagnostic_leaves_translations_unaligned(self):
        record = dict(lang_code='en', senses=[dict(glosses=['artificial'])],
                      translations=[dict(code='ru', word='artificial', sense='artificial')])
        self.assertEqual(english_edition_shape(record), dict(
            senses=1, senses_with_gloss_array=1, top_level_translation_candidates=1,
        ))
        with self.assertRaises(ValueError):
            english_edition_shape(record | dict(senses=['invalid']))
