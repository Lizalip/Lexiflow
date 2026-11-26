import unittest

from research_russian_qa import sample_senses


class RussianQaTest(unittest.TestCase):
    def test_sampling_is_order_independent_and_preserves_one_sense_per_spelling(self):
        def row(key,sense,pos):
            return dict(key=key,sense_id=sense,pos=pos,sense_count=3,translations=['x','y','z'])
        rows = [row('договор','1','n'),row('договор','2','n'),row('метод','3','n'),
                row('делать','4','v'),row('новый','5','adj')]
        frequency = {('делать','v'):2}
        selected = sample_senses(rows,frequency)
        self.assertEqual(selected,sample_senses(reversed(rows),frequency))
        self.assertEqual(len(selected),len({x['key'] for x in selected}))
        self.assertEqual({'договор':'business','метод':'academic','делать':'frequent_verb','новый':'adjective'},
                         {x['key']:x['stratum'] for x in selected})
