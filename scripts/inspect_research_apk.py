"""Report APK payload, ZIP layout, and the exact dictionary assets."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile


ASSET_PREFIX = 'assets/dictionary-research/'
ANDROID_PROFILE_ASSETS = {'assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'}
DICTIONARY_FILES = {'dictionary.sqlite','manifest.json'}
PASSPORT_APK_BYTES = 100_000_000


def sha256(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(256*1024),b''):
        digest.update(block)
    return digest.hexdigest()


def signing_start(stream,central):
    if central<32:
        return central
    stream.seek(central-24)
    footer = stream.read(24)
    if footer[8:]!=b'APK Sig Block 42':
        return central
    block_size, = struct.unpack_from('<Q',footer)
    start = central-block_size-8
    if block_size<24 or start<0:
        raise ValueError('invalid APK Signing Block')
    stream.seek(start)
    if struct.unpack('<Q',stream.read(8))[0]!=block_size:
        raise ValueError('APK Signing Block header does not match footer')
    return start


def entry_end(stream,entry,limit):
    stream.seek(entry.header_offset)
    header = stream.read(30)
    if len(header)!=30 or not header.startswith(b'PK\x03\x04'):
        raise ValueError('invalid ZIP local header')
    name_size, extra_size = struct.unpack_from('<HH',header,26)
    end = entry.header_offset+30+name_size+extra_size+entry.compress_size
    if entry.flag_bits & 8:
        stream.seek(end)
        descriptor = stream.read(16)
        if descriptor.startswith(b'PK\x07\x08'):
            descriptor = descriptor[4:]
            end += 4
        expected = (entry.CRC,entry.compress_size,entry.file_size)
        if len(descriptor)<12 or struct.unpack_from('<III',descriptor)!=expected:
            raise ValueError('unexpected or ZIP64 data descriptor')
        end += 12
    if end>limit:
        raise ValueError('ZIP entry overlaps signing block or central directory')
    return end


def inspect(path,*,pair=None,plain_release=False):
    groups = defaultdict(lambda: dict(uncompressed_bytes=0,compressed_bytes=0,entries=0))
    rows = {}
    gaps = []
    with ZipFile(path) as archive,path.open('rb') as stream:
        limit = signing_start(stream,archive.start_dir)
        previous = 0
        for entry in sorted(archive.infolist(),key=lambda value: value.header_offset):
            if entry.filename in rows or entry.header_offset<previous:
                raise ValueError('duplicate path or overlapping ZIP entries')
            gap = entry.header_offset-previous
            if gap:
                gaps.append(dict(offset=previous,bytes=gap,before=entry.filename))
            previous = entry_end(stream,entry,limit)
            group = 'dex' if entry.filename.endswith('.dex') else (
                'resources' if entry.filename.startswith('res/') or entry.filename=='resources.arsc'
                else entry.filename.split('/',1)[0])
            aggregate = groups[group]
            aggregate['uncompressed_bytes'] += entry.file_size
            aggregate['compressed_bytes'] += entry.compress_size
            aggregate['entries'] += 1
            rows[entry.filename] = dict(path=entry.filename,method=entry.compress_type,
                uncompressed_bytes=entry.file_size,compressed_bytes=entry.compress_size,
                compressed_ratio=round(entry.compress_size/entry.file_size,5) if entry.file_size else None)
        if previous<limit:
            gaps.append(dict(offset=previous,bytes=limit-previous,before='signing block or central directory'))
        assets = {name for name in rows if name.startswith('assets/') and not name.endswith('/')}
        expected = {ASSET_PREFIX+name for name in DICTIONARY_FILES}
        unexpected = sorted(assets-ANDROID_PROFILE_ASSETS-(set() if plain_release else expected))
        dictionary = {}
        if pair is not None:
            if unexpected or expected-assets:
                raise ValueError('expected exactly one Research dictionary pair and no other assets')
            for name in sorted(DICTIONARY_FILES):
                with archive.open(ASSET_PREFIX+name) as actual,(pair/name).open('rb') as original:
                    digest = sha256(actual)
                    if digest!=sha256(original):
                        raise ValueError(f'Research asset SHA-256 mismatch: {name}')
                dictionary[name] = dict(rows[ASSET_PREFIX+name],sha256=digest)
        if plain_release and unexpected:
            raise ValueError('ordinary release contains unexpected assets')
        return dict(apk_bytes=path.stat().st_size,
                    compressed_payload_bytes=sum(r['compressed_bytes'] for r in rows.values()),
                    zip_unreferenced_bytes=sum(g['bytes'] for g in gaps),
                    largest_zip_gaps=sorted(gaps,key=lambda gap: -gap['bytes'])[:8],
                    signing_block_bytes=archive.start_dir-limit,
                    central_and_footer_bytes=path.stat().st_size-archive.start_dir,
                    entries=len(rows),groups=dict(groups),dictionary=dictionary,
                    unexpected_assets=unexpected,
                    largest_entries=sorted(rows.values(),key=lambda row: -row['compressed_bytes'])[:12]), rows


def compare(before,after):
    def delta(path):
        old,new = before.get(path,{}),after.get(path,{})
        return dict(path=path,old_method=old.get('method'),new_method=new.get('method'),
                    payload_delta=new.get('compressed_bytes',0)-old.get('compressed_bytes',0),
                    original_delta=new.get('uncompressed_bytes',0)-old.get('uncompressed_bytes',0))
    differences = [delta(name) for name in before.keys() | after.keys() if before.get(name)!=after.get(name)]
    return sorted(differences,key=lambda item: (-abs(item['payload_delta']),item['path']))


def equivalent_entries(previous,current):
    with ZipFile(previous) as before, ZipFile(current) as after:
        files = before.namelist()
        if set(files)!=set(after.namelist()):
            return False
        for name in files:
            with before.open(name) as left, after.open(name) as right:
                if sha256(left)!=sha256(right):
                    return False
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path)
    parser.add_argument('--compare',type=Path,help='previous APK for comparison')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--pair',type=Path,help='enforce the selected Research source pair')
    mode.add_argument('--plain-release',action='store_true',help='enforce an ordinary asset-free release')
    parser.add_argument('--require-compact',action='store_true',
                        help='enforce passport size and reject stale unreferenced archive space')
    parser.add_argument('--require-equivalent',action='store_true',help='compare every uncompressed ZIP entry by SHA-256')
    args = parser.parse_args()
    if args.require_equivalent and args.compare is None:
        parser.error('--require-equivalent needs --compare')
    report,current = inspect(args.apk,pair=args.pair,plain_release=args.plain_release)
    if args.compare:
        before,previous = inspect(args.compare)
        report['comparison'] = dict(previous_apk_bytes=before['apk_bytes'],
            previous_zip_unreferenced_bytes=before['zip_unreferenced_bytes'],
            apk_delta=report['apk_bytes']-before['apk_bytes'],
            payload_delta=report['compressed_payload_bytes']-before['compressed_payload_bytes'],
            changed_entries=compare(previous,current))
        if args.require_equivalent:
            report['comparison']['equivalent_entries'] = equivalent_entries(args.compare,args.apk)
            if not report['comparison']['equivalent_entries']:
                parser.exit(1,'APK payload entries differ\n')
    print(json.dumps(report,indent=2,ensure_ascii=True))
    if args.require_compact and (report['apk_bytes']>PASSPORT_APK_BYTES or report['zip_unreferenced_bytes']!=0):
        parser.exit(1,'APK exceeds passport size or has unreferenced ZIP space\n')


if __name__ == '__main__':
    main()
