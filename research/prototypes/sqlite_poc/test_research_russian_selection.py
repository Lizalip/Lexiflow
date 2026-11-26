import unittest
from collections import Counter

from research_russian_selection import coverage, select, tally


class RussianSelectionTest(unittest.TestCase):
    def test_counts_exact_lemma_and_pos_and_skips_non_tokens(self):
        def row(index, lemma, pos):
            return f'{index}\t_\t{lemma}\t{pos}\t_\t_\t_\t_\t_\t_\n'

        self.assertEqual(tally([
            '# metadata\n', row('1','Ёж','NOUN'), row('2','ёж','NOUN'),
            row('3','Еж','NOUN'), row('4','ёж','PROPN'), row('5.1','ёж','NOUN'),
            row('5-6','ёж','NOUN'), row('6','ёж','ADJ'), row('7','lat','NOUN'),
        ]), Counter({('ёж','n'): 2, ('еж','n'): 1, ('ёж','adj'): 1}))

    def test_prefers_development_frequency_without_displacing_existing_types(self):
        installed = {('same','n')}
        unknown = ('unavailable','n')
        new = ('same','v')
        development = Counter({unknown: 50, new: 15, ('other','n'): 5})
        supplement, balanced = select(development, installed, {new, ('other','n')}|installed)
        self.assertEqual(supplement, [new, ('other','n')])
        self.assertEqual(balanced, supplement)
        holdout = Counter({('same','n'): 10, new: 5, unknown: 1})
        self.assertEqual(coverage(holdout, installed|set(supplement))['all'],
                         dict(types=3,types_covered=2,tokens=16,tokens_covered=15))
