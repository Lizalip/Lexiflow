import tempfile
import unittest
from pathlib import Path

from editor_workspace import write_html


class WorkspaceTest(unittest.TestCase):
    def test_imported_content_is_escaped_and_export_stays_proposed(self):
        attack = '</script><img src=x onerror=alert(1)>'
        row = dict(entry_id=attack,sense_id='s',snapshot_sha256='a'*64,locator=attack,
                   candidate=attack,headword=attack,pos=attack,source=attack,translation_rights='pending',
                   alternatives=2,priority='specialist',reason=attack,
                   context=[dict(text=attack,sense_id='child',locator=attack,rights='pending')])
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)/'review.html'
            write_html(out,[row])
            text = out.read_text(encoding='utf-8')
            self.assertNotIn(attack,text)
            self.assertIn('&lt;img',text)
            self.assertIn('\\u003c/script>',text)
            self.assertIn('decision:"proposed"',text)
            self.assertNotIn('decision:"approved"',text)
            self.assertNotIn('<script src=',text)
