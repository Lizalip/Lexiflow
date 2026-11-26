import unittest

from research_bilingual import supplement_rows


class BilingualSupplementTest(unittest.TestCase):
    def test_only_adds_selected_missing_part_of_speech(self):
        noun = ('source-noun','fixture','n')
        verb = ('source-verb','fixture','v')
        others = {'fixture':[noun,verb]}
        self.assertEqual([verb],supplement_rows(others,[['fixture','v']],{('fixture','n')}))
        for selection,current in (
            ([['fixture','n']],{('fixture','n')}),
            ([['fixture','v'],['fixture','v']],set()),
            ([['fixture','adj']],set()),
            ([['missing','n']],set()),
            ([['fixture',1]],set()),
        ):
            with self.assertRaises(ValueError):
                supplement_rows(others,selection,current)
