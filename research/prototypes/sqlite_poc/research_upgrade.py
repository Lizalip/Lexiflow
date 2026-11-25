"""Check that an expanded research dictionary preserves learning identities."""
import argparse
import json
from pathlib import Path

from release_package import checksum, connect
from research_package import DATA, validate_research_pair


def differences(db, projection, table, owner, base_table):
    subset = f" WHERE {owner} IN (SELECT id FROM main.{base_table})"
    before = f"SELECT {projection} FROM main.{table}"
    after = f"SELECT {projection} FROM expanded.{table}{subset}"
    return sum(db.execute(f"SELECT count(*) FROM ({left} EXCEPT {right})").fetchone()[0]
               for left,right in ((before,after),(after,before)))


def compare(before, after):
    early = validate_research_pair(before)
    late = validate_research_pair(after)
    if early['sources'] != late['sources'] or early['policies'] != late['policies']:
        raise ValueError('source snapshot or policy changed')
    def decoded(manifest, key):
        row = manifest['attributions'].get(key)
        if row is not None and manifest['package_format'] == 3:
            return [row[0],row[1],manifest['modifications'][row[2]]]
        return row
    for key in early['attributions']:
        if decoded(late,key) != decoded(early,key):
            raise ValueError('existing attribution changed')
    early_notes = early.get('source_entry_notes',{}).get('entries',{})
    late_notes = late.get('source_entry_notes',{}).get('entries',{})
    with connect(before/'dictionary.sqlite','ro') as db:
        db.execute('ATTACH DATABASE ? AS expanded',((after/'dictionary.sqlite').resolve().as_uri()+'?mode=ro',))
        checks = {
            'entries': differences(db,'id,spelling,pos,search_key,attribution_id','entry','id','entry'),
            'senses': differences(db,'id,entry_id,position,attribution_id','sense','entry_id','entry'),
            'translations': differences(db,'sense_id,wording,search_key,attribution_id,origin_attribution_id',
                                        'translation','sense_id','sense'),
        }
        checks['source_notes'] = sum(early_notes.get(key) != late_notes.get(key)
                                     for key, in db.execute('SELECT id FROM main.entry'))
        counts = {schema:{'spellings':db.execute(f'SELECT count(DISTINCT search_key) FROM {schema}.entry').fetchone()[0],
                          'entries':manifest['counts']['entry'],'senses':manifest['counts']['sense']}
                  for schema,manifest in (('main',early),('expanded',late))}
    if any(checks.values()) or counts['expanded']['spellings'] <= counts['main']['spellings']:
        raise ValueError(f'not a compatible expansion: {checks}')
    return dict(before=counts['main'],after=counts['expanded'],differences=checks,
                preserved_attributions=len(early['attributions']),
                before_manifest_sha256=checksum(before/'manifest.json'),
                after_manifest_sha256=checksum(after/'manifest.json'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before',type=Path)
    parser.add_argument('after',type=Path)
    args = parser.parse_args()
    if not all(pair.resolve().is_relative_to((DATA/'processed').resolve()) for pair in (args.before,args.after)):
        parser.error('use local pairs under research/data/processed')
    print(json.dumps(compare(args.before,args.after),indent=2))


if __name__ == '__main__':
    main()
