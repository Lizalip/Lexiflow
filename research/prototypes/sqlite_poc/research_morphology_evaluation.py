"""Measure morphology against fixed lexical probes chosen independently of package contents."""
import argparse
import csv
from pathlib import Path

from release_package import connect
from research_package import DATA

# These probes supplement the earlier irregular-form smoke set. The meanings and
# tags below specify one valid reading per query, not an exhaustive answer list.
PROBES = (
    ('jumped','jump','v','Past'),('swam','swim','v','Past'),
    ('drove','drive','v','Past'),('broke','break','v','Past'),
    ('grown','grow','v','Past participle'),('driven','drive','v','Past participle'),
    ('spoken','speak','v','Past participle'),('forgotten','forget','v','Past participle'),
    ('singing','sing','v','Present participle'),('dancing','dance','v','Present participle'),
    ('cooking','cook','v','Present participle'),('studying','study','v','Present participle'),
    ('eats','eat','v','Third-person singular'),('tries','try','v','Third-person singular'),
    ('walks','walk','v','Third-person singular'),('speaks','speak','v','Third-person singular'),
    ('geese','goose','n','Plural'),('feet','foot','n','Plural'),
    ('teeth','tooth','n','Plural'),('boxes','box','n','Plural'),
    ('faster','fast','adj','Comparative'),('heavier','heavy','adj','Comparative'),
    ('longest','long','adj','Superlative'),('largest','large','adj','Superlative'),
    ('qzttzz','',None,None),('writed','',None,None),
)


def evaluate(package):
    with connect(package/'dictionary.sqlite','ro') as db:
        for query,lemma,pos,feature in PROBES:
            exact = db.execute("SELECT count(*) FROM entry WHERE language='en' AND search_key=?",(query,)).fetchone()[0]
            analyses = set(db.execute("""
                SELECT e.search_key,e.pos,m.value FROM form f JOIN entry e ON e.id=f.entry_id
                JOIN lexical_metadata m ON m.form_id=f.id
                WHERE f.search_key=? AND e.language='en' AND m.kind='grammar'
            """,(query,)))
            yield dict(package=package.name,query=query,expected_lemma=lemma,expected_pos=pos,
                       expected_feature=feature,expected_found=(lemma,pos,feature) in analyses if lemma else not analyses and not exact,
                       exact_headwords=exact,analyses=len(analyses))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    parser.add_argument('packages',type=Path,nargs='+')
    args = parser.parse_args()
    if not all(p.resolve().is_relative_to((DATA/'processed').resolve()) for p in [args.output,*args.packages]):
        parser.error('use local processed packages and a fresh processed output')
    rows = [row for package in args.packages for row in evaluate(package)]
    with args.output.open('x',encoding='utf-8',newline='') as stream:
        writer = csv.DictWriter(stream,fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)


if __name__ == '__main__':
    main()
