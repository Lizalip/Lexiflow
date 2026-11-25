"""Compare a direct FreeDict RU–EN staging snapshot and EN–RU reverse index."""
import argparse
import json
from collections import defaultdict
from pathlib import Path

from corpus_enrichment import RUS_ENG_SHA, SOURCE, VERSION
from prototype import integrity, normalize
from release_package import connect
from research_evaluation import HOLDOUT
from research_package import DATA, validate_research_pair

FOCUS = ('понимать','устойчивый','исследование','признак','вывод','предположение','вероятность',
         'оценка','устойчивость','значение','обучение','разработка','ограничение','доказательство','отношение')


def verified_direct(db):
    if db.execute("SELECT s.name,r.sha256,r.version FROM release r JOIN source s ON s.id=r.source_id").fetchall() != [
            (SOURCE,RUS_ENG_SHA,VERSION)]:
        raise ValueError('expected verified direct FreeDict staging')
    integrity(db)


def direct_candidates(db,query):
    return [dict(entry_id=e,sense_id=s,part_of_speech=pos,russian=word,
                 english=value,sense_locator=sl,translation_locator=tl,snapshot=RUS_ENG_SHA)
            for e,s,pos,word,value,sl,tl in db.execute("""
                SELECT e.id,s.id,e.pos,e.spelling,g.body,se.locator,ge.locator
                FROM entry e JOIN sense s ON s.entry_id=e.id JOIN gloss g ON g.sense_id=s.id
                JOIN evidence se ON se.id=s.evidence_id JOIN evidence ge ON ge.id=g.evidence_id
                WHERE e.lang='ru' AND e.search_key=? AND g.lang='en' AND g.role='source_gloss'
                    AND se.rights!='excluded' AND ge.rights!='excluded'
                ORDER BY e.id,s.position,g.position LIMIT 50
            """,(normalize(query),))]


def compare(direct, reverse):
    forward_keys = defaultdict(set)
    inverted_keys = defaultdict(set)
    for key,word,pos in direct.execute("""
        SELECT DISTINCT e.search_key,g.body,e.pos FROM entry e JOIN sense s ON s.entry_id=e.id
        JOIN gloss g ON g.sense_id=s.id JOIN evidence ge ON ge.id=g.evidence_id
        WHERE e.lang='ru' AND g.lang='en' AND g.role='source_gloss' AND ge.rights!='excluded'
    """):
        forward_keys[key].add((normalize(word),pos))
    for key,word,pos in reverse.execute("""
        SELECT DISTINCT t.search_key,e.search_key,e.pos FROM translation t
        JOIN sense s ON s.id=t.sense_id JOIN entry e ON e.id=s.entry_id
    """):
        inverted_keys[key].add((word,pos))
    common = forward_keys.keys() & inverted_keys.keys()
    direct_only = forward_keys.keys() - inverted_keys.keys()
    reverse_only = inverted_keys.keys() - forward_keys.keys()

    def evidence(key):
        a = forward_keys.get(key,set())
        b = inverted_keys.get(key,set())
        ae,be = ({word for word,_ in values} for values in (a,b))
        return dict(direct=len(ae),reverse=len(be),shared_equivalents=len(ae&be),
                    new_direct_equivalents=len(ae-be),shared_equivalents_with_exact_pos=len(a&b))

    details = {key:evidence(key) for key in sorted(common)}
    named = tuple(dict.fromkeys((*FOCUS,*HOLDOUT['reverse'].split(','))))
    return dict(direct_searchable_russian_keys=len(forward_keys),reverse_russian_keys=len(inverted_keys),
                shared_russian_keys=len(common),direct_only_russian_keys=len(direct_only),
                reverse_only_russian_keys=len(reverse_only),
                shared_keys_with_no_equal_english=sum(row['shared_equivalents']==0 for row in details.values()),
                shared_keys_with_new_direct_equivalent=sum(row['new_direct_equivalents']>0 for row in details.values()),
                shared_keys_with_english_and_exact_pos=sum(row['shared_equivalents_with_exact_pos']>0 for row in details.values()),
                focused={key:evidence(normalize(key)) for key in named})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('direct',type=Path)
    parser.add_argument('reverse_package',type=Path)
    parser.add_argument('--query',help='show provenance for a single local direct query')
    args = parser.parse_args()
    if not all(path.resolve().is_relative_to((DATA/'processed').resolve())
               for path in (args.direct,args.reverse_package)):
        parser.error('use local sources under research/data/processed')
    manifest = validate_research_pair(args.reverse_package)
    with connect(args.direct,'ro') as direct, connect(args.reverse_package/'dictionary.sqlite','ro') as reverse:
        verified_direct(direct)
        result = (dict(direct_results=direct_candidates(direct,args.query)) if args.query else compare(direct,reverse))
    print(json.dumps(dict(reverse_corpus=manifest['corpus_version'],**result),ensure_ascii=True,indent=2))


if __name__ == '__main__':
    main()
