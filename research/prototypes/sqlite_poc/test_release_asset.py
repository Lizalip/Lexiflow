import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from debug_package import make
from prepare_release_asset import activate, prepare
from release_package import checksum


class ReleaseAssetTest(unittest.TestCase):
    def test_design_preview_is_rich_and_cannot_be_bundled_for_release(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            preview = root/'preview'
            counts = make(preview,2,context=True,preview=True)['counts']
            self.assertEqual(counts['entry'],6)
            self.assertEqual(counts['sense'],8)
            self.assertEqual(counts['translation'],9)
            self.assertEqual(counts['example_translation'],2)
            self.assertEqual(counts['form'],2)
            with self.assertRaisesRegex(ValueError,'synthetic'):
                prepare(preview,root/'assets',checksum(preview/'manifest.json'))

    def test_interrupted_asset_swap_restores_the_complete_previous_pair(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            old, candidate, backup = [root/name for name in ('active','candidate','previous')]
            for path, marker in ((old,b'old'),(candidate,b'new')):
                path.mkdir()
                for filename in ('dictionary.sqlite','manifest.json'):
                    (path/filename).write_bytes(marker)
            original_rename = Path.rename

            def interrupted(path,target):
                if path == candidate:
                    raise OSError('fixture interruption')
                return original_rename(path,target)

            with patch.object(Path,'rename',interrupted):
                with self.assertRaisesRegex(OSError,'fixture interruption'):
                    activate(candidate,old,backup)
            self.assertFalse(backup.exists())
            self.assertEqual((old/'manifest.json').read_bytes(),b'old')
            self.assertEqual((old/'dictionary.sqlite').read_bytes(),b'old')

    def test_approved_hash_cannot_bypass_synthetic_or_checksum_checks(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            source = root/'package'
            make(source,2,context=True)
            manifest = source/'manifest.json'
            destination = root/'assets'
            with self.assertRaisesRegex(ValueError,'explicitly approved SHA'):
                prepare(source,destination,'a'*64)
            with self.assertRaisesRegex(ValueError,'synthetic'):
                prepare(source,destination,checksum(manifest))
            data = json.loads(manifest.read_text(encoding='utf-8'))
            data['corpus_version'] = 'renamed-fixture'
            manifest.write_text(json.dumps(data),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'synthetic'):
                prepare(source,destination,checksum(manifest))
            with (source/'dictionary.sqlite').open('ab') as stream:
                stream.write(b'corruption')
            with self.assertRaisesRegex(ValueError,'checksum'):
                prepare(source,destination,checksum(manifest))
            self.assertFalse((destination/'dictionary-release').exists())
