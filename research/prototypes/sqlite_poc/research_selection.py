"""Compare fixed-budget FreeDict selections without altering the packaged corpus."""
import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

from candidate_rank import subtlex_frequency
from prototype import normalize
from release_package import checksum, connect
from research_cefr import POS, workbook_rows
from research_evaluation import probe_set
from research_package import CORE, DATA, FREQUENCY_SNAPSHOT, screen, select, verified_release


def benchmark(groups,frequency,cefr,size):
    starter,_ = select(groups,frequency,min(size,5000))
    existing,_ = select(groups,frequency,size)
    ordered = sorted(groups,key=lambda key:(-frequency.get(key,0),' ' in key,key))
    expression_keys = {key for key in existing if ' ' in key}
    parts = {key:{row[2] for row in rows} for key,rows in groups.items()}
    educational = defaultdict(set)
    for _,key,pos,level in cefr:
        if POS.get(pos) and POS[pos] in parts.get(key,()):
            educational[key].add(level)
    # Preserve the starter and all current multiword keys. The remaining budget
    # includes available CEFR-J lemma/POS candidates and then frequency priority.
    pool = starter | expression_keys | educational.keys()
    mixed = set(pool)
    if len(mixed)>size:
        raise ValueError('review strata exceed selection budget')
    mixed.update([key for key in ordered if key not in pool][:size-len(mixed)])
    variants = {'frequency_only':set(ordered[:size]),'current_expression_quota':existing,
                'starter_expressions_and_cefr_candidates':mixed}
    evaluation = defaultdict(set)
    for category,word in probe_set():
        if category!='reverse':
            evaluation[category].add(normalize(word))
    reports = {}
    for policy,keys in variants.items():
        coverage = {category:len(keys & probes) for category,probes in evaluation.items()}
        reports[policy] = dict(spellings=len(keys),retained_starter=len(starter & keys),
                               overlap_with_current=len(existing & keys),multiword_keys=sum(' ' in key for key in keys),
                               keys_with_observed_frequency=sum(frequency.get(key,0)>0 for key in keys),
                               education_candidates=len(keys & educational.keys()),
                               education_level_candidates_nonexclusive=dict(Counter(
                                   level for key in keys for level in educational.get(key,()))),
                               heldout_exact_headword_matches=sum(coverage.values()),heldout_by_category=coverage)
    return dict(eligible_spellings=len(groups),comparison_budget=size,
                exact_english_probes=sum(map(len,evaluation.values())),variants=reports)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('staging',type=Path)
    parser.add_argument('frequency',type=Path)
    parser.add_argument('cefr',type=Path)
    args = parser.parse_args()
    if not args.staging.resolve().is_relative_to((DATA/'processed').resolve()):
        parser.error('use local FreeDict staging')
    if checksum(args.frequency)!=FREQUENCY_SNAPSHOT:
        parser.error('frequency archive does not match pinned digest')
    with connect(args.staging,'ro') as db:
        release = verified_release(db)
        groups,_ = screen(db.execute(CORE,(release,)*3))
    print(json.dumps(benchmark(groups,subtlex_frequency(args.frequency),workbook_rows(args.cefr),20000),indent=2))


if __name__ == '__main__':
    main()
