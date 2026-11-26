"""Read bounded official Tatoeba API captures and create local example candidates."""
import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from prototype import normalize
from release_package import BASE_USAGE_CAPTURES, FINAL_USAGE_CAPTURES, checksum, connect
from research_package import DATA, validate_research_pair
from research_reverse_aliases import stress_key

CAPTURES = {key:BASE_USAGE_CAPTURES[key] for key in ('opened','believe','chance')}
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


def usage_candidates(db,captures,manifest):
    """Index bounded token phrases once, then return article-level candidates for review."""
    keys = defaultdict(set)
    for entry,key,pos in db.execute("SELECT id,search_key,pos FROM entry WHERE language='en'"):
        if pos in ('n','v','adj','adv'):
            keys[tuple(WORD.findall(key))].add((entry,pos,'lemma',key))
    for entry,pos,key in db.execute("""SELECT DISTINCT e.id,e.pos,f.search_key FROM form f
          JOIN entry e ON e.id=f.entry_id JOIN lexical_metadata m ON m.form_id=f.id
          WHERE e.language='en' AND m.kind='grammar'"""):
        keys[tuple(WORD.findall(key))].add((entry,pos,'form',key))
    current = manifest.get('entry_usage_examples',{}).get('entries',{})
    if manifest.get('entry_usage_examples',{}).get('version')==1:
        current = {entry:[example] for entry,example in current.items()}
    seen = {(entry,int(manifest['attributions'][row['en']['evidence']][1].split('/')[1]))
            for entry,items in current.items() for row in items}
    shortlisted = {}
    stats = Counter()
    for capture,document in sorted(captures.items()):
        for en,ru in pairs(document):
            stats['direct_pairs'] += 1
            tokens = WORD.findall(normalize(en['text']))
            if not 2<=len(tokens)<=20 or not en['text'].rstrip().endswith(('.', '?', '!')):
                continue
            for start in range(len(tokens)):
                for end in range(start+1,min(start+5,len(tokens))+1):
                    matches = keys.get(tuple(tokens[start:end]),())
                    if len(matches)!=1:
                        stats['ambiguous_tokens'] += bool(matches)
                        continue
                    entry,pos,relationship,key = next(iter(matches))
                    identity = entry,en['id'],ru['id']
                    if (entry,en['id']) in seen or identity in shortlisted or len(current.get(entry,[]))>=8:
                        continue
                    shortlisted[identity] = dict(entry=entry,pos=pos,relationship=relationship,token=key,
                                                 original=en['id'],translation=ru['id'],capture=capture,
                                                 english=en,russian=ru,word_count=len(tokens),
                                                 existing_examples=len(current.get(entry,[])),
                                                 status='requires preliminary lexical review')
    candidates = sorted(shortlisted.values(),key=lambda row:(
        row['existing_examples'],row['relationship']!='lemma',abs(row['word_count']-8),
        -len(row['token'].split()),row['original'],row['translation'],row['entry']))
    return dict(stats,candidates=len(candidates),candidate_articles=len({row['entry'] for row in candidates})),candidates


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package',type=Path)
    parser.add_argument('captures',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--usage-candidates',action='store_true')
    parser.add_argument('--final-captures',action='store_true')
    args = parser.parse_args()
    for path,kind in ((args.package,'processed'),(args.captures,'interim'),(args.output,'review')):
        if not path.resolve().is_relative_to((DATA/kind).resolve()):
            parser.error(f'path must stay in research/data/{kind}')
    if args.final_captures and not args.usage_candidates:
        parser.error('--final-captures requires --usage-candidates')
    manifest = validate_research_pair(args.package)
    counts = {}
    combined = []
    with connect(args.package/'dictionary.sqlite','ro') as db:
        pages = {}
        registry = FINAL_USAGE_CAPTURES if args.final_captures else BASE_USAGE_CAPTURES if args.usage_candidates else CAPTURES
        for query,digest in registry.items():
            path = args.captures/f'{query}.json'
            if path.stat().st_size>256*1024 or checksum(path)!=digest:
                raise ValueError('unexpected Tatoeba capture')
            with path.open(encoding='utf-8') as stream:
                page = json.load(stream)
            if args.usage_candidates:
                pages[query] = page
            else:
                counts[query],rows = suggestions(db,query,page)
                combined.extend(rows)
        if args.usage_candidates:
            metrics,combined = usage_candidates(db,pages,manifest)
            counts['article_usage'] = metrics
    with args.output.open('x',encoding='utf-8',newline='\n') as stream:
        json.dump(combined,stream,ensure_ascii=False,indent=2)
        stream.write('\n')
    total = Counter()
    for metrics in counts.values():
        total.update(metrics)
    print(json.dumps(dict(total=dict(total),per_query=counts,imported_examples=0),indent=2))


if __name__ == '__main__':
    main()
