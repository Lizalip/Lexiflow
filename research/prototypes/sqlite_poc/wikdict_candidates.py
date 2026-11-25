"""Read-only probe for locally supplied WikDict generic pair SQLite snapshots."""
import argparse
import json
from collections import Counter
from pathlib import Path

from diagnostics import diagnostic_spellings
from prototype import normalize
from release_package import checksum, connect

PAIRS = ('en-ru','ru-en')
REQUIRED = {'lexentry','sense_num','sense','written_rep','trans_list','score','is_good','importance'}


def candidates(db, pair):
    if pair not in PAIRS:
        raise ValueError('expected en-ru or ru-en')
    db.execute('PRAGMA query_only=ON')
    db.execute('PRAGMA trusted_schema=OFF')
    tables = {row[1]:row[2] for row in db.execute('PRAGMA main.table_list')}
    columns = {row[1] for row in db.execute('PRAGMA main.table_info(translation)')}
    if tables.get('translation') != 'table' or not REQUIRED <= columns:
        raise ValueError('unexpected WikDict generic translation table')
    for rowid, entry, num, sense, spelling, translations, score, flag, importance in db.execute("""
        SELECT rowid,lexentry,sense_num,sense,written_rep,trans_list,score,is_good,importance
        FROM main.translation ORDER BY rowid
    """):
        if not isinstance(spelling,str) or not normalize(spelling) or not isinstance(translations,str):
            raise ValueError('invalid WikDict spelling or translation list')
        yield dict(locator=f'{pair}/translation/rowid/{rowid}', lexentry=entry, sense_num=num,
                   definition_hint=sense, headword=spelling, raw_translation_list=translations,
                   producer_score=score, producer_flag=flag, producer_importance=importance,
                   rights='pending')


def measure(path, pair):
    digest = checksum(path)
    targets = {normalize(word) for word in diagnostic_spellings()} if pair == 'en-ru' else set()
    spellings = set()
    senses = set()
    counts = Counter()
    with connect(path,'ro') as db:
        for row in candidates(db,pair):
            count_key = normalize(row['headword'])
            spellings.add(count_key)
            counts['rows'] += 1
            counts['rows_with_producer_flag'] += row['producer_flag'] == 1
            counts['nonempty_lists'] += bool(row['raw_translation_list'].strip())
            if row['lexentry'] is not None and row['sense_num'] is not None:
                senses.add((row['lexentry'],row['sense_num']))
            else:
                counts['rows_without_sense_key'] += 1
    if checksum(path) != digest:
        raise ValueError('SQLite snapshot changed during inspection')
    return dict(pair=pair, sha256=digest, database_bytes=path.stat().st_size,
                normalized_spellings=len(spellings), explicit_sense_keys=len(senses),
                diagnostic_covered=len(targets & spellings) if targets else None,counts=dict(counts))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path',type=Path)
    parser.add_argument('pair',choices=PAIRS)
    args = parser.parse_args()
    print(json.dumps(measure(args.path,args.pair),indent=2))
