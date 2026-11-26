import tempfile
import unittest
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

from inspect_research_apk import equivalent_entries, inspect


class ResearchApkInspectionTest(unittest.TestCase):
    def test_reports_gap_and_same_payload_despite_different_archive_layouts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old = root/'before.apk'
            new = root/'after.apk'
            old.write_bytes(b'unreferenced bytes')
            with ZipFile(old,'a',compression=ZIP_STORED) as archive:
                archive.writestr('classes.dex',b'fixture'*100)
            with ZipFile(new,'w',compression=ZIP_DEFLATED) as archive:
                archive.writestr('classes.dex',b'fixture'*100)
            self.assertTrue(equivalent_entries(old,new))
            self.assertEqual(inspect(old)[0]['zip_unreferenced_bytes'],len(b'unreferenced bytes'))
            self.assertEqual(inspect(new)[0]['zip_unreferenced_bytes'],0)

    def test_checks_exact_assets_and_their_actual_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root/'fixture.apk'
            for name in ('dictionary.sqlite','manifest.json'):
                (root/name).write_bytes(name.encode())
            def write(extra=False):
                with ZipFile(apk,'w') as archive:
                    for name in ('dictionary.sqlite','manifest.json'):
                        archive.write(root/name,arcname=f'assets/dictionary-research/{name}')
                    if extra:
                        archive.writestr('assets/stale-fixture.txt',b'unexpected')
            write()
            self.assertEqual(inspect(apk,pair=root)[0]['unexpected_assets'],[])
            with self.assertRaisesRegex(ValueError,'release'):
                inspect(apk,plain_release=True)
            (root/'manifest.json').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'SHA-256'):
                inspect(apk,pair=root)
            write(extra=True)
            with self.assertRaisesRegex(ValueError,'exactly one'):
                inspect(apk,pair=root)

    def test_release_allows_android_baseline_profile_but_no_dictionary(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary)/'release.apk'
            with ZipFile(apk,'w') as archive:
                archive.writestr('assets/dexopt/baseline.prof',b'profile')
                archive.writestr('assets/dexopt/baseline.profm',b'metadata')
            self.assertEqual(inspect(apk,plain_release=True)[0]['unexpected_assets'],[])
            with ZipFile(apk,'a') as archive:
                archive.writestr('assets/dictionary/dictionary.sqlite',b'dictionary')
            with self.assertRaisesRegex(ValueError,'release'):
                inspect(apk,plain_release=True)


if __name__ == '__main__':
    unittest.main()
