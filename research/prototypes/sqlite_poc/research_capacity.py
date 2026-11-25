"""Measure the full FreeDict export and prototype an attribution string table."""
import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from time import perf_counter

from candidate_rank import subtlex_frequency
from release_package import MAX_DATABASE, MAX_MANIFEST, checksum, connect
from research_package import DATA, FREQUENCY_SNAPSHOT, SNAPSHOT, build


def json_size(value):
    return len(json.dumps(value,ensure_ascii=True,separators=(',',':')))


def capacity(database,manifest,payload):
    attrs = payload['attributions']
    modifications = Counter(row[2] for row in attrs.values())
    indices = {text:index for index,text in enumerate(sorted(modifications))}
    repeated_bytes = sum(count*json_size(text) for text,count in modifications.items())
    # Only replace each repeated JSON string value by its pool index.
    # The original strings and canonical SHA-256 keys remain reconstructable.
    projected_delta = (sum(count*json_size(indices[text]) for text,count in modifications.items())
                       +json_size('modifications')+2+json_size(sorted(modifications))-repeated_bytes)
    manifest_bytes = manifest.stat().st_size
    dictionary_bytes = database.stat().st_size
    oversized = [name for name,size,limit in (('manifest',manifest_bytes,MAX_MANIFEST),
                 ('sqlite',dictionary_bytes,MAX_DATABASE)) if size>limit]
    return dict(counts=payload['counts'],sqlite_bytes=dictionary_bytes,manifest_bytes=manifest_bytes,
                sqlite_limit=MAX_DATABASE,manifest_limit=MAX_MANIFEST,oversized=oversized,
                attribution_records=len(attrs),attribution_policy_count=len(payload['policies']),
                attribution_policy_json_bytes=json_size(payload['policies']),
                attribution_sha_key_json_bytes=sum(json_size(key) for key in attrs),
                locator_json_bytes=sum(json_size(link[1]) for link in attrs.values()),
                modification_distinct_strings=len(modifications),modification_json_bytes=repeated_bytes,
                projected_string_table_manifest_bytes=manifest_bytes+projected_delta,
                attribution_python_shallow_bytes=sys.getsizeof(attrs)+sum(
                    sys.getsizeof(key)+sys.getsizeof(link)+sys.getsizeof(link[1]) for key,link in attrs.items()),
                single_pair_bytes=manifest_bytes+dictionary_bytes,
                two_full_pairs_bytes=2*(manifest_bytes+dictionary_bytes))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('staging',type=Path)
    parser.add_argument('archive',type=Path)
    parser.add_argument('frequency',type=Path)
    parser.add_argument('scratch_pair',type=Path)
    args = parser.parse_args()
    processed = (DATA/'processed').resolve()
    if not all(p.resolve().is_relative_to(processed) for p in (args.staging,args.scratch_pair)):
        parser.error('use local processed staging and a fresh scratch directory')
    if checksum(args.archive)!=SNAPSHOT or checksum(args.frequency)!=FREQUENCY_SNAPSHOT:
        parser.error('pinned source or frequency snapshot mismatch')
    report = {}
    started = perf_counter()
    with connect(args.staging,'ro') as source:
        source.execute('BEGIN')
        try:
            build(source,subtlex_frequency(args.frequency),args.scratch_pair,None,
                  lambda database,manifest,payload: report.update(capacity(database,manifest,payload)))
        except ValueError as error:
            if str(error) != 'dictionary package exceeds size limit' or not report.get('oversized') or args.scratch_pair.exists():
                raise
            report['valid_package'] = False
        else:
            report['valid_package'] = True
    report['elapsed_seconds'] = round(perf_counter()-started,3)
    print(json.dumps(report,indent=2))


if __name__ == '__main__':
    main()
