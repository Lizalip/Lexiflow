import sqlite3
import unittest
from contextlib import closing

from release_package import DISTRIBUTION_BILINGUAL, expand_attributions, validate_ud_forms
from research_form_package import add_forms


class ResearchFormPackageTest(unittest.TestCase):
    def test_all_mode_adds_only_attested_analyses_and_keeps_pilot_rows(self):
        with closing(sqlite3.connect(':memory:')) as db:
            db.executescript(DISTRIBUTION_BILINGUAL)
            db.executemany('INSERT INTO entry VALUES (?,?,?,?,?,?)',[
                ('go-v','go','v','go','fixture','en'),
                ('run-v','run','v','run','fixture','en'),
                ('fast-adv','fast','adv','fast','fixture','en'),
            ])
            manifest = dict(schema_version=3, corpus_version='research-fixture', policies=[], sources=[],
                            modifications=[], attributions={}, counts=dict(form=0,lexical_metadata=0))
            prefix = '# sent_id = fixture'
            past = '1\twent\tgo\tVERB\t_\tTense=Past|VerbForm=Fin\t_\t_\t_\t_'
            add_forms(manifest,db,{'test':[prefix,past]})
            original = db.execute('SELECT id,attribution_id FROM form').fetchone()
            rows = [prefix,past,
                    '2\trunning\trun\tVERB\t_\tTense=Pres|VerbForm=Part\t_\t_\t_\t_',
                    '3\trunning\trun\tVERB\t_\tVerbForm=Ger\t_\t_\t_\t_',
                    '4\truns\trun\tVERB\t_\tNumber=Sing|Person=3|Tense=Pres|VerbForm=Fin\t_\t_\t_\t_',
                    '5\tfastest\tfast\tADV\t_\tDegree=Sup\t_\t_\t_\t_',
                    '6\tgoes\tgo\tAUX\t_\tNumber=Sing|Person=3|Tense=Pres|VerbForm=Fin\t_\t_\t_\t_']
            metrics = add_forms(manifest,db,{'dev':rows},all_forms=True)
            self.assertEqual(metrics['preserved_analyses'],1)
            self.assertEqual(metrics['forms'],4)
            self.assertEqual(metrics['distinct_surfaces'],3)
            self.assertEqual(db.execute("SELECT id,attribution_id FROM form WHERE spelling='went'").fetchone(),original)
            self.assertEqual({row[0] for row in db.execute('SELECT value FROM lexical_metadata')},
                             {'Past','Present participle','Gerund','Third-person singular','Superlative'})
            validate_ud_forms(db,expand_attributions(manifest['policies'],manifest['attributions'],
                                                     manifest['modifications']),3)

    def test_form_keeps_feature_and_rejects_wrong_pos_or_ambiguous_owner(self):
        with closing(sqlite3.connect(':memory:')) as db:
            db.executescript(DISTRIBUTION_BILINGUAL)
            db.execute("INSERT INTO entry VALUES ('testing','go','v','go','fixture','en')")
            db.execute("INSERT INTO entry VALUES ('duplicate1','take','v','take','fixture','en')")
            db.execute("INSERT INTO entry VALUES ('duplicate2','take','v','take','fixture','en')")
            manifest = dict(schema_version=3, corpus_version='research-fixture', policies=[], sources=[],
                            modifications=[], attributions={}, counts=dict(form=0,lexical_metadata=0))
            rows = ['# sent_id = fixture', '1\twent\tgo\tVERB\t_\tTense=Past|VerbForm=Fin\t_\t_\t_\t_',
                    '2\ttook\ttake\tVERB\t_\tTense=Past|VerbForm=Fin\t_\t_\t_\t_',
                    '3\tgone\tgo\tVERB\t_\t_\t_\t_\t_\t_']
            self.assertEqual(add_forms(manifest,db,{'test':rows})['forms'],1)
            self.assertEqual(db.execute('SELECT spelling,value FROM form JOIN lexical_metadata ON form.id=form_id')
                             .fetchone(),('went','Past'))
            attrs = expand_attributions(manifest['policies'],manifest['attributions'],manifest['modifications'])
            validate_ud_forms(db,attrs,3)
            db.execute("UPDATE entry SET pos='n' WHERE id='testing'")
            with self.assertRaisesRegex(ValueError,'provenance'):
                validate_ud_forms(db,attrs,3)
