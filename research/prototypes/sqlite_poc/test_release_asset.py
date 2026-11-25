import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from debug_package import make
from prepare_release_asset import prepare
from release_package import checksum


class ReleaseAssetTest(unittest.TestCase):
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
