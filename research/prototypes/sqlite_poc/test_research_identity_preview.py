import unittest

from research_identity_preview import preview


class ResearchIdentityPreviewTest(unittest.TestCase):
    def test_only_exact_unchanged_bindings_are_reversible(self):
        old = [dict(id='a',kind='sense',snapshot='snapshot-a',locator='one',fingerprint='same'),
               dict(id='b',kind='sense',snapshot='snapshot-a',locator='two',fingerprint='old'),
               dict(id='c',kind='sense',snapshot='snapshot-a',locator='three',fingerprint='same')]
        new = [dict(old[0],id='x'),dict(old[1],id='y',fingerprint='changed'),
               dict(old[2],id='z',snapshot='snapshot-b')]
        rows = preview(old,new)
        self.assertEqual([(row['status'],row['production_id']) for row in rows],
                         [('unchanged','x'),('changed',None),('removed',None)])
        undo = {row['production_id']:row['research_id'] for row in rows if row['production_id']}
        self.assertEqual(undo,{'x':'a'})
        with self.assertRaises(ValueError):
            preview(old,[*new,dict(new[0],locator='four')])
