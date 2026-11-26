import copy
import sqlite3
import unittest
from contextlib import closing

from release_package import expand_attributions, validate_entry_usage
from research_example_package import add_curated_examples, add_example


class ResearchUsageExampleTest(unittest.TestCase):
    def test_curated_v2_checks_owner_whole_token_and_distinct_sentences(self):
        def linked(original, translated, text, *, owner='fixture-owner',language='rus'):
            return dict(id=original,lang='eng',text=text,owner=owner,license='CC BY 2.0 FR',
                        is_unapproved=False,translations=[dict(id=translated,lang=language,text='Дословный тест.',
                        owner='fixture-translator',license='CC BY 2.0 FR',is_unapproved=False,is_direct=True)])

        capture = {'data':[linked(11,12,'A fine sample.'),linked(21,22,'Another sample appears.'),
                           linked(31,32,'This is a sampler.'),linked(41,42,'A sample.',owner=None),
                           linked(51,52,'A sample.',language='eng')]}
        original = dict(schema_version=3,modifications=[],policies=[],attributions={},
                        sources=[],corpus_version='research-fixture')
        decision = dict(entry='fixture',capture='believe',original=11,translation=12,relationship='lemma',
                        token='sample',pos='n',reason='Synthetic test relationship.')
        with closing(sqlite3.connect(':memory:')) as db:
            db.execute('CREATE TABLE entry(id,language,search_key,pos)')
            db.execute("INSERT INTO entry VALUES ('fixture','en','sample','n')")
            manifest = copy.deepcopy(original)
            self.assertEqual(add_curated_examples(manifest,db,[decision,dict(decision,original=21,translation=22)],
                                                  {'believe':capture}),dict(articles=1,examples=2))
            attrs = expand_attributions(manifest['policies'],manifest['attributions'],manifest['modifications'])
            validate_entry_usage(db,manifest['entry_usage_examples'],attrs)
            initial = copy.deepcopy(manifest['entry_usage_examples']['entries']['fixture'])
            with self.assertRaisesRegex(ValueError,'unique'):
                add_curated_examples(manifest,db,[decision],{'believe':capture})
            extra = {'data':[linked(61,62,'This sample helps.')]}
            self.assertEqual(add_curated_examples(manifest,db,[dict(decision,original=61,translation=62)],
                                                  {'believe':extra})['examples'],3)
            self.assertEqual(manifest['entry_usage_examples']['entries']['fixture'][:2],initial)
            validate_entry_usage(db,manifest['entry_usage_examples'],
                                 expand_attributions(manifest['policies'],manifest['attributions'],manifest['modifications']))
            for rejected in ([decision,decision],[dict(decision,pos='v')],[dict(decision,original=31,translation=32)],
                             [dict(decision,original=41,translation=42)],[dict(decision,original=51,translation=52)]):
                with self.assertRaises(ValueError):
                    add_curated_examples(copy.deepcopy(original),db,rejected,{'believe':capture})

    def test_export_and_reject_retagged_or_changed_sentences(self):
        capture = {'data':[{'id':11,'lang':'eng','text':'A chance appears.','owner':'fixture-owner',
                            'license':'CC BY 2.0 FR','is_unapproved':False,'translations':[
            {'id':12,'lang':'rus','text':'Учебный текст.','owner':'fixture-translator',
             'license':'CC BY 2.0 FR','is_unapproved':False,'is_direct':True}]}]}
        manifest = dict(schema_version=3,modifications=[],policies=[],attributions={},
                        sources=[],corpus_version='research-fixture')
        with closing(sqlite3.connect(':memory:')) as db:
            db.execute('CREATE TABLE entry(id,language,search_key,pos)')
            db.execute("INSERT INTO entry VALUES ('fixture','en','chance','n')")
            add_example(manifest,db,capture,11,12)
            extension = manifest['entry_usage_examples']
            attrs = expand_attributions(manifest['policies'],manifest['attributions'],manifest['modifications'])
            validate_entry_usage(db,extension,attrs)

            wrong_text = copy.deepcopy(extension)
            wrong_text['entries']['fixture']['ru']['text'] += '!'
            wrong_role = dict(version=1,entries={'fixture':{'en':extension['entries']['fixture']['ru'],
                                                           'ru':extension['entries']['fixture']['en']}})
            for invalid in (wrong_text,wrong_role):
                with self.assertRaises(ValueError):
                    validate_entry_usage(db,invalid,attrs)

            db.execute("UPDATE entry SET pos='v'")
            with self.assertRaisesRegex(ValueError,'owner'):
                validate_entry_usage(db,extension,attrs)


if __name__ == '__main__':
    unittest.main()
