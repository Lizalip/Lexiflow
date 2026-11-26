"""Create a separate, deterministic sample of direct FreeDict RU–EN senses."""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

from prototype import normalize
from release_package import checksum, connect
from research_package import DATA, SOURCE_BY_LANGUAGE, validate_research_pair
from research_russian_selection import UD_FILES, tally

ACADEMIC = frozenset("""исследование метод вывод доказательство гипотеза оценка вероятность признак
результат переменная теория опыт причина источник пример анализ выводить объяснять понимать
описание отношение процесс сравнение развитие система правило методика структура алгоритм""".split())
BUSINESS = frozenset("""работа деньги заказ цена рынок покупка счёт доход расход налог договор
документ компания фирма банк товар услуга продавать покупать платить сделка прибыль клиент
стоимость кредит бухгалтер касса сделка работа продажа бюджет предприниматель""".split())
QUOTAS = {'academic':10,'business':10,'everyday':15,'frequent_verb':15,'noun':15,
          'adjective':15,'polysemous':20,'multiple_equivalents':20}


def sample_senses(entries, frequency):
    criteria = {
        'academic':lambda row: row['key'] in ACADEMIC,
        'business':lambda row: row['key'] in BUSINESS,
        'everyday':lambda row: frequency.get((row['key'],row['pos']),0)>=3,
        'frequent_verb':lambda row: row['pos']=='v' and frequency.get((row['key'],'v'),0)>=1,
        'noun':lambda row: row['pos']=='n',
        'adjective':lambda row: row['pos']=='adj',
        'polysemous':lambda row: row['sense_count']>=3,
        'multiple_equivalents':lambda row: len(row['translations'])>=3,
    }
    ordered = sorted(entries,key=lambda row: hashlib.sha256(
        ('lexiflow-ru-editorial-v1/'+row['sense_id']).encode()).digest())
    used = set()
    result = []
    for group,goal in QUOTAS.items():
        count = 0
        for row in ordered:
            if row['key'] in used or not criteria[group](row):
                continue
            result.append(dict(row,stratum=group))
            used.add(row['key'])
            count += 1
            if count==goal:
                break
    return result


def read_senses(db, manifest):
    attrs = manifest['attributions']
    definitions = manifest.get('source_entry_notes',{}).get('entries',{})
    snapshot = SOURCE_BY_LANGUAGE['ru'][0]
    current = {}
    for entry,spelling,key,pos,sense,position,attribution,sense_count in db.execute("""
        SELECT e.id,e.spelling,e.search_key,e.pos,s.id,s.position,s.attribution_id,
               (SELECT count(*) FROM sense WHERE entry_id=e.id)
        FROM sense s JOIN entry e ON e.id=s.entry_id WHERE e.language='ru'
    """):
        current[sense] = dict(entry_id=entry,headword=spelling,key=key,pos=pos,sense_id=sense,
                              sense_locator=attrs[attribution][1],snapshot=snapshot,position=position,
                              sense_count=sense_count,translations=[],assessment='pending')
    for sense,text,attribution in db.execute("""
        SELECT sense_id,wording,attribution_id FROM translation WHERE language='en' ORDER BY id
    """):
        current[sense]['translations'].append(dict(text=text,locator=attrs[attribution][1]))
    reverse = set(row[0] for row in db.execute(
        "SELECT DISTINCT search_key FROM translation WHERE language='ru'"))
    for row in current.values():
        row['exact_reverse_also_available'] = row['key'] in reverse
        row['source_context'] = definitions.get(row['entry_id'],[])
    return current.values()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package',type=Path)
    parser.add_argument('gd_dev',type=Path)
    parser.add_argument('output',type=Path)
    args = parser.parse_args()
    for path,kind in ((args.package,'processed'),(args.gd_dev,'interim'),(args.output,'review')):
        if not path.resolve().is_relative_to((DATA/kind).resolve()):
            parser.error(f'use paths under research/data/{kind}')
    if checksum(args.gd_dev)!=UD_FILES['dev']:
        parser.error('UD Russian GSD development checksum mismatch')
    manifest = validate_research_pair(args.package)
    with args.gd_dev.open(encoding='utf-8') as ud, connect(args.package/'dictionary.sqlite','ro') as db:
        sample = sample_senses(read_senses(db,manifest),tally(ud))
    if not sample:
        parser.error('no direct Russian records selected')
    with args.output.open('x',encoding='utf-8') as output:
        json.dump(sample,output,ensure_ascii=False,indent=2)
    print(json.dumps(dict(senses=len(sample),strata=Counter(row['stratum'] for row in sample),
        parts_of_speech=Counter(row['pos'] for row in sample),
        source_context=sum(bool(row['source_context']) for row in sample),
        exact_reverse=sum(row['exact_reverse_also_available'] for row in sample),
        human_reviews=0,preliminary_assessments=0),indent=2))


if __name__ == '__main__':
    main()
