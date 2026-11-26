"""Check that an expanded research dictionary preserves learning identities."""
import argparse
import json
from pathlib import Path

from release_package import checksum, connect, expand_attributions
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
    if early['schema_version'] != late['schema_version'] or not set(map(tuple,early['sources'])) <= set(map(tuple,late['sources'])):
        raise ValueError('schema or existing source snapshot changed')
    old_attrs = expand_attributions(early['policies'],early['attributions'],early.get('modifications'))
    new_attrs = expand_attributions(late['policies'],late['attributions'],late.get('modifications'))
    if any(new_attrs.get(key) != value for key,value in old_attrs.items()):
        raise ValueError('existing attribution changed')
    language = ',language' if early['schema_version']==3 else ''
    with connect(before/'dictionary.sqlite','ro') as db:
        db.execute('ATTACH DATABASE ? AS expanded',((after/'dictionary.sqlite').resolve().as_uri()+'?mode=ro',))
        checks = {
            'entries': differences(db,'id,spelling,pos,search_key,attribution_id'+language,'entry','id','entry'),
            'senses': differences(db,'id,entry_id,position,attribution_id','sense','entry_id','entry'),
            'translations': differences(db,'sense_id,wording,search_key,attribution_id,origin_attribution_id'+language,
                                        'translation','sense_id','sense'),
        }
        for extension in ('source_entry_notes','entry_cefr','entry_usage_examples'):
            prior = early.get(extension,{}).get('entries',{})
            offered = late.get(extension,{}).get('entries',{})
            checks[extension] = sum(offered.get(key) != value for key,value in prior.items())
        counts = {}
        for schema,manifest in (('main',early),('expanded',late)):
            grouped = db.execute(f'SELECT language,count(DISTINCT search_key) FROM {schema}.entry GROUP BY language').fetchall() \
                if language else [('en',db.execute(f'SELECT count(DISTINCT search_key) FROM {schema}.entry').fetchone()[0])]
            counts[schema] = dict(spellings=sum(count for _,count in grouped),headwords=dict(grouped),
                                  entries=manifest['counts']['entry'],senses=manifest['counts']['sense'],
                                  translations=manifest['counts']['translation'])
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
