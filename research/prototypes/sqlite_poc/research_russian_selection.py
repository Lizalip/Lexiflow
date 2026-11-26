"""Evaluate supplemental Russian lemma/POS coverage on held-out UD GSD tokens."""
import argparse
import json
from collections import Counter
from pathlib import Path

from prototype import normalize
from release_package import checksum, connect
from research_bilingual import RUSSIAN, screening
from research_package import DATA
from research_reverse import verified_direct

UD_FILES = {
    'dev': 'e3e3d4e8d1d7544e531b2b81ae64257efacc97f93276ce6d2ce52fd1de052fa2',
    'test': 'f26e022329162a1c6306f76644d06f770f1572501755421165387137fe63138d',
}
PARTS = dict(NOUN='n', VERB='v', ADJ='adj', ADV='adv', PRON='pronoun', ADP='preposition',
             CCONJ='conjunction', SCONJ='conjunction', INTJ='interjection', PART='particle')


def tally(lines):
    counts = Counter()
    for line in lines:
        if not line.strip() or line.startswith('#'):
            continue
        fields = line.rstrip('\r\n').split('\t')
        if len(fields) != 10:
            raise ValueError('invalid CoNLL-U token')
        index, _, lemma, upos = fields[:4]
        if index.isascii() and index.isdecimal() and upos in PARTS and RUSSIAN.fullmatch(lemma):
            counts[normalize(lemma), PARTS[upos]] += 1
    return counts


def select(development, current, available):
    ranked = sorted(development, key=lambda key: (-development[key], key))
    supplement = [key for key in ranked[:500] if key in available and key not in current]
    pos_counts = Counter()
    pos_ranks = {}
    for key in ranked:
        if key in available and key not in current:
            pos_counts[key[1]] += 1
            pos_ranks[key] = pos_counts[key[1]]
    balanced = sorted(pos_ranks, key=lambda key: (pos_ranks[key], -development[key], key))[:len(supplement)]
    return supplement, balanced


def coverage(holdout, selected):
    table = {}
    for part in ('all', *sorted(set(PARTS.values()))):
        keys = {key for key in holdout if part == 'all' or key[1] == part}
        matched = keys & selected
        table[part] = dict(types=len(keys), types_covered=len(matched),
                           tokens=sum(holdout[key] for key in keys),
                           tokens_covered=sum(holdout[key] for key in matched))
    return table


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ud_dir', type=Path)
    parser.add_argument('package', type=Path)
    parser.add_argument('staging', type=Path)
    parser.add_argument('local_candidates', type=Path)
    args = parser.parse_args()
    for path, directory in ((args.ud_dir,'interim'), (args.package,'processed'),
                            (args.staging,'processed'), (args.local_candidates,'review')):
        if not path.resolve().is_relative_to((DATA/directory).resolve()):
            parser.error(f'use paths under research/data/{directory}')
    splits = {}
    for split, digest in UD_FILES.items():
        path = args.ud_dir/f'ru_gsd-ud-{split}.conllu'
        if checksum(path) != digest:
            parser.error(f'UD Russian GSD r2.16 {split} checksum mismatch')
        with path.open(encoding='utf-8') as source:
            splits[split] = tally(source)
    database = args.package/'dictionary.sqlite'
    with connect(database,'ro') as db:
        current = set(db.execute("SELECT DISTINCT search_key,pos FROM entry WHERE language='ru'"))
    with connect(args.staging,'ro') as source:
        verified_direct(source)
        candidates,_ = screening(source,set(),500)
        available = {(key,row[2]) for key,rows in candidates.items() for row in rows}
    supplement,balanced = select(splits['dev'],current,available)
    with args.local_candidates.open('x',encoding='utf-8') as output:
        json.dump(dict(frequency=supplement, balanced=balanced),output,ensure_ascii=False,indent=2)
        output.write('\n')
    print(json.dumps(dict(source='UD Russian GSD r2.16, CC BY-SA 4.0', sha256=UD_FILES,
        baseline_database_sha256=checksum(database),
        development_types=len(splits['dev']), development_tokens=sum(splits['dev'].values()),
        current_types=len(current), frequency_additions=len(supplement), balanced_additions=len(balanced),
        current=coverage(splits['test'],current), screened_pool=coverage(splits['test'],available),
        frequency=coverage(splits['test'],current|set(supplement)), balanced=coverage(splits['test'],current|set(balanced))),
        indent=2))


if __name__ == '__main__':
    main()
