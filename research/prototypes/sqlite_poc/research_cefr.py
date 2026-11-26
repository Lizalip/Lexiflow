"""Compare the official CEFR-J Wordlist with FreeDict at headword/POS level."""
import argparse
import json
from collections import Counter, defaultdict
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile

from prototype import normalize
from release_package import CEFR_SHA, checksum, connect
from research_package import DATA, validate_research_pair

SNAPSHOT = CEFR_SHA
MEMBER = 'CEFR-J Wordlist Ver1.6.xlsx'
LEVELS = {'A1','A2','B1','B2'}
POS = {'noun':'n','verb':'v','adjective':'adj','adverb':'adv','determiner':'determiner',
       'preposition':'preposition','conjunction':'conjunction','pronoun':'pronoun',
       'interjection':'interjection','number':'numeral'}


def parse_rows(rows):
    iterator = iter(rows)
    if tuple(next(iterator,()))[:3] != ('headword','pos','CEFR'):
        raise ValueError('unexpected CEFR-J columns')
    for number,row in enumerate(iterator,2):
        word,pos,level = row[:3]
        if not isinstance(word,str) or not normalize(word) or not isinstance(pos,str) or level not in LEVELS:
            raise ValueError(f'unexpected CEFR-J row {number}')
        yield number,normalize(word),pos,level


def workbook_rows(path):
    if path.stat().st_size > 10*1024**2 or checksum(path) != SNAPSHOT:
        raise ValueError('expected the pinned CEFR-J 1.6 archive')
    from openpyxl import load_workbook
    with ZipFile(path) as archive:
        members = archive.infolist()
        if len(members)!=1 or members[0].filename!=MEMBER or members[0].file_size>10*1024**2:
            raise ValueError('unexpected CEFR-J workbook')
        workbook = load_workbook(BytesIO(archive.read(members[0])),read_only=True,data_only=True)
        try:
            # Only the publisher's already-separated sheet supplies aliases.
            return list(parse_rows(workbook['ALL_sep'].iter_rows(values_only=True)))
        finally:
            workbook.close()


def compare(db,rows):
    lexicon = defaultdict(set)
    for key,pos in db.execute('SELECT DISTINCT search_key,pos FROM entry'):
        lexicon[key].add(pos)
    candidates = defaultdict(lambda:defaultdict(set))
    unmatched = Counter()
    for line,key,pos,level in rows:
        lexical_pos = POS.get(pos)
        if key not in lexicon:
            unmatched['absent_headword'] += 1
        elif lexical_pos is None:
            unmatched['unsupported_pos_mapping'] += 1
        elif lexical_pos not in lexicon[key]:
            unmatched['headword_only'] += 1
        else:
            candidates[(key,lexical_pos)][level].add(line)
    labels = Counter()
    ambiguous = 0
    for levels in candidates.values():
        if len(levels)==1:
            labels.update(levels.keys())
        else:
            ambiguous += 1
    return dict(source_rows=len(rows),source_levels=dict(Counter(row[3] for row in rows)),
                source_normalized_headwords=len({row[1] for row in rows}),candidate_lemma_pos_pairs=len(candidates),
                unambiguous_candidate_levels=dict(labels),multiple_levels_on_same_lemma_pos=ambiguous,
                unmatched_rows=dict(unmatched),assigned_sense_levels=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive',type=Path)
    parser.add_argument('packages',type=Path,nargs='+')
    args = parser.parse_args()
    rows = workbook_rows(args.archive)
    results = []
    for path in args.packages:
        if not path.resolve().is_relative_to((DATA/'processed').resolve()):
            parser.error('use a local research package under research/data/processed')
        manifest = validate_research_pair(path)
        with connect(path/'dictionary.sqlite','ro') as db:
            results.append(dict(corpus=manifest['corpus_version'],**compare(db,rows)))
    print(json.dumps(results,indent=2))


if __name__ == '__main__':
    main()
