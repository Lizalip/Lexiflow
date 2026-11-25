"""Stage a specifically authorized dictionary for the APK-signed release build."""
import argparse
import re
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from release_package import MAX_DATABASE, MAX_MANIFEST, checksum, expand_attributions, validate_package

FILES = {'manifest.json':MAX_MANIFEST,'dictionary.sqlite':MAX_DATABASE}


def check_directory(directory):
    if directory.is_symlink() or (directory.exists() and (not directory.is_dir() or any(
            path.name not in FILES or path.is_symlink() or not path.is_file() for path in directory.iterdir()))):
        raise ValueError('unexpected release backup contents')


def clear_backup(backup):
    check_directory(backup)
    for filename in FILES:
        (backup/filename).unlink(missing_ok=True)
    backup.rmdir()


def activate(candidate, destination, backup):
    """Swap complete directory pairs in the same filesystem; never copy over a working pair."""
    if backup.exists():
        if destination.exists():
            clear_backup(backup)
        else:
            backup.rename(destination)
    if destination.exists():
        destination.rename(backup)
    try:
        candidate.rename(destination)
    except OSError:
        if backup.exists() and not destination.exists():
            backup.rename(destination)
        raise
    if backup.exists():
        clear_backup(backup)


def prepare(source, generated_root, approved_sha256):
    if not re.fullmatch('[0-9a-f]{64}',approved_sha256):
        raise ValueError('an explicitly approved manifest SHA-256 is required')
    generated_root.mkdir(parents=True,exist_ok=True)
    destination = generated_root/'dictionary-release'
    backup = generated_root.parent/(generated_root.name+'-previous')
    for path in (destination,backup):
        check_directory(path)
    # Keep the backup and incomplete workspace outside the Gradle assets root.
    if backup.exists() and not destination.exists():
        backup.rename(destination)
    with TemporaryDirectory(dir=generated_root.parent) as tmp:
        candidate = Path(tmp)/'validated-pair'
        candidate.mkdir()
        for filename,limit in FILES.items():
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
        activate(candidate,destination,backup)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path)
    parser.add_argument('generated_root',type=Path)
    parser.add_argument('approved_sha256')
    args = parser.parse_args()
    prepare(args.source,args.generated_root,args.approved_sha256)
