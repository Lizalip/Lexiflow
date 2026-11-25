"""Stage a specifically authorized dictionary for the APK-signed release build."""
import argparse
import re
import shutil
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from release_package import MAX_DATABASE, MAX_MANIFEST, checksum, expand_attributions, validate_package


def prepare(source, generated_root, approved_sha256):
    if not re.fullmatch('[0-9a-f]{64}',approved_sha256):
        raise ValueError('an explicitly approved manifest SHA-256 is required')
    files = {'manifest.json':MAX_MANIFEST,'dictionary.sqlite':MAX_DATABASE}
    generated_root.mkdir(parents=True,exist_ok=True)
    destination = generated_root/'dictionary-release'
    with TemporaryDirectory(dir=generated_root) as tmp:
        candidate = Path(tmp)
        for filename,limit in files.items():
            path = source/filename
            if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
                raise ValueError('invalid release asset input')
            with path.open('rb') as stream, (candidate/filename).open('xb') as output:
                remaining = limit
                while chunk := stream.read(min(remaining+1,1024**2)):
                    remaining -= len(chunk)
                    if remaining < 0:
                        raise ValueError('release asset grew beyond size limit')
                    output.write(chunk)
        if checksum(candidate/'manifest.json') != approved_sha256:
            raise ValueError('manifest does not match the explicitly approved SHA-256')
        data = validate_package(candidate)
        attrs = data['attributions']
        if data['package_format'] == 2:
            attrs = expand_attributions(data['policies'],attrs)
        if (data['corpus_version'].casefold().startswith('synthetic-')
                or any(urlsplit(row['source_url']).hostname.endswith('.invalid')
                       or row['license_id'].casefold() in ('synthetic','artificial-fixture')
                       for row in attrs.values())):
            raise ValueError('synthetic content cannot be supplied to a release build')
        destination.mkdir(exist_ok=True)
        for filename in files:
            shutil.copyfile(candidate/filename,destination/filename)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path)
    parser.add_argument('generated_root',type=Path)
    parser.add_argument('approved_sha256')
    args = parser.parse_args()
    prepare(args.source,args.generated_root,args.approved_sha256)
