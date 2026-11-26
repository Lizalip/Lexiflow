"""Read bounded official Tatoeba API captures and create local example candidates."""
import argparse
import json
import re
from collections import Counter
from pathlib import Path

from prototype import normalize
from release_package import USAGE_CAPTURES, checksum, connect
from research_package import DATA, validate_research_pair
from research_reverse_aliases import stress_key

CAPTURES = {key:USAGE_CAPTURES[key] for key in ('opened','believe','chance')}
WORD = re.compile(r"[^\W_]+(?:['’-][^\W_]+)*")
LICENSES = {'CC BY 2.0 FR':'https://creativecommons.org/licenses/by/2.0/fr/',
            'CC0 1.0':'https://creativecommons.org/publicdomain/zero/1.0/'}


def sentence(row,lang):
    if not isinstance(row,dict) or row.get('lang')!=lang or row.get('is_unapproved') is not False:
        return None
    identifier,text,owner,license_id = (row.get(key) for key in ('id','text','owner','license'))
    if (type(identifier) is not int or identifier<=0 or not isinstance(text,str) or not 1<=len(text)<=240
            or not text.strip() or '\n' in text or '://' in text or not isinstance(owner,str) or not owner.strip()
            or license_id not in LICENSES):
        return None
    return dict(id=identifier,text=text,attribution_name=owner,license=license_id,
                terms=LICENSES[license_id],source=f'https://tatoeba.org/en/sentences/show/{identifier}')


def pairs(page):
    data = page.get('data')
    if not isinstance(data,list) or len(data)>100:
        raise ValueError('unexpected Tatoeba page')
    for row in data:
        source = sentence(row,'eng')
        if source is None or not isinstance(row.get('translations'),list):
            continue
        for translation in row['translations']:
            if not isinstance(translation,dict) or translation.get('is_direct') is not True:
                continue
            target = sentence(translation,'rus')
            if target is not None:
                yield source,target


def token_phrase(needle,haystack):
    return any(haystack[i:i+len(needle)]==needle for i in range(len(haystack)-len(needle)+1)) if needle else False


def suggestions(db,search,capture):
    hints = [(sense,WORD.findall(stress_key(value))) for sense,value in db.execute("""
        SELECT s.id,t.wording FROM entry e JOIN sense s ON s.entry_id=e.id JOIN translation t ON t.sense_id=s.id
        WHERE e.search_key=?
    """,(normalize(search),))]
    sources = set()
    examined = set()
    result = []
    for english,russian in pairs(capture):
        sources.add(english['id'])
        identity = english['id'],russian['id']
        if identity in examined:
            continue
        examined.add(identity)
        eng = WORD.findall(normalize(english['text']))
        if not token_phrase(WORD.findall(normalize(search)),eng):
            continue
        ru = WORD.findall(stress_key(russian['text']))
        suggested = sorted({sense for sense,hint in hints if token_phrase(hint,ru)})
        result.append(dict(query=search,english=english,russian=russian,word_count=len(eng),
                           candidate_sense_ids=suggested,status='separate example candidate'))
    result.sort(key=lambda row:(len(row['candidate_sense_ids'])!=1,abs(row['word_count']-8),
                                row['english']['id'],row['russian']['id']))
    return dict(api_source_rows=len(capture['data']),eligible_source_rows=len(sources),
                direct_links_with_text_and_license=len(examined),exact_phrase_pairs=len(result),
                pairs_with_unique_sense_hint=sum(len(row['candidate_sense_ids'])==1 for row in result)),result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package',type=Path)
    parser.add_argument('captures',type=Path)
    parser.add_argument('output',type=Path)
    args = parser.parse_args()
    for path,kind in ((args.package,'processed'),(args.captures,'interim'),(args.output,'review')):
        if not path.resolve().is_relative_to((DATA/kind).resolve()):
            parser.error(f'path must stay in research/data/{kind}')
    validate_research_pair(args.package)
    counts = {}
    combined = []
    with connect(args.package/'dictionary.sqlite','ro') as db:
        for query,digest in CAPTURES.items():
            path = args.captures/f'{query}.json'
            if path.stat().st_size>256*1024 or checksum(path)!=digest:
                raise ValueError('unexpected Tatoeba capture')
            with path.open(encoding='utf-8') as stream:
                counts[query],rows = suggestions(db,query,json.load(stream))
            combined.extend(rows)
    with args.output.open('x',encoding='utf-8',newline='\n') as stream:
        json.dump(combined,stream,ensure_ascii=False,indent=2)
        stream.write('\n')
    total = Counter()
    for metrics in counts.values():
        total.update(metrics)
    print(json.dumps(dict(total=dict(total),per_query=counts,imported_examples=0),indent=2))


if __name__ == '__main__':
    main()
