import sqlite3
import unittest
from contextlib import closing

from research_morphology_evaluation import KAIKKI_SNAPSHOT, independent_probes


class MorphologyEvaluationTest(unittest.TestCase):
    def test_cross_source_selection_excludes_unsupported_tags_and_repeated_evidence(self):
        with closing(sqlite3.connect(':memory:')) as source:
            source.executescript("""
                CREATE TABLE release(sha256);
                CREATE TABLE entry(id,search_key,pos,lang);
                CREATE TABLE form(entry_id,search_key,raw_metadata,evidence_id);
                CREATE TABLE evidence(id,locator,rights);
                INSERT INTO entry VALUES (1,'sample','verb','en'),(2,'sample','noun','en');
                INSERT INTO evidence VALUES (1,'a','pending'),(2,'b','pending'),(3,'c','excluded');
                INSERT INTO form VALUES (1,'sampled','{"tags":["participle","past"]}',1),
                    (1,'sampled','{"tags":["past","participle"]}',2),
                    (2,'sampled','{"tags":["past"]}',1),
                    (1,'samples','{"tags":["singular","third-person"]}',3);
            """)
            with self.assertRaisesRegex(ValueError,'snapshot'):
                independent_probes(source)
            source.execute('INSERT INTO release VALUES (?)',(KAIKKI_SNAPSHOT,))
            self.assertEqual({('sampled','sample','v','Past participle'):'a'},independent_probes(source))
