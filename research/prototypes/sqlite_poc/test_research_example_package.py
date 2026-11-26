import copy
import sqlite3
import unittest
from contextlib import closing

from release_package import expand_attributions, validate_entry_usage
from research_example_package import add_example


class ResearchUsageExampleTest(unittest.TestCase):
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
