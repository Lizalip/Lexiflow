import unittest

from research_editorial_audit import match_assessments


class EditorialAuditTest(unittest.TestCase):
    def test_preserves_legacy_locator_lists_and_rejects_mismatch_or_duplicates(self):
        item = dict(sense_id='sense',entry_id='entry',sense_locator='root/sense',stratum='fixture',
                    snapshot='a'*64,translations=[dict(locator='root/gloss',text='example')],pos='n')
        assessment = dict(sense_id='sense',entry_id='entry',sense_locator='root/sense',stratum='fixture',
                          snapshot='a'*64,translation_locators=['root/gloss'],assessment='requires_context',
                          reason='fixture rationale',human_review=False,method='AI-assisted preliminary')
        self.assertEqual('fixture rationale',match_assessments([item],[('batch',[assessment])])['sense']['rationale'])
        for changed in (dict(assessment,translation_locators=['elsewhere']),
                        dict(assessment,human_review=True),dict(assessment,snapshot='b'*64)):
            with self.assertRaises(ValueError):
                match_assessments([item],[('batch',[changed])])
        with self.assertRaisesRegex(ValueError,'duplicate'):
            match_assessments([item],[('batch',[assessment,assessment])])
