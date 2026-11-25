"""Reproducible held-out lexical probes for existing FreeDict research packages."""
import argparse
import json
from pathlib import Path

from prototype import normalize
from release_package import checksum, connect
from research_diagnostics import QUERIES as DEVELOPMENT, search
from research_package import DATA, validate_research_pair

HOLDOUT = {
    'everyday_candidate': 'bread,door,window,kitchen,chair,bus,park,ticket,milk,bed,shoe,bottle',
    'intermediate': 'borrow,repair,arrive,deliver,refuse,improve,suggest,replace,invite,disturb,manage,prepare',
    'advanced': 'inadvertent,perfunctory,tenacious,ubiquitous,precarious,meticulous,elusive,discrepancy,conundrum,arduous,impeccable,coherent',
    'function_word': 'among,whereas,unless,until,beneath,within,throughout,neither,each,either,alongside,despite',
    'polysemous_verb': 'charge,draw,pass,drop,raise,turn,drive,catch,bear,stand,break,strike',
    'irregular_verb': 'arise,bleed,bind,cling,creep,forbid,freeze,seek,shine,sink,swear,weave',
    'noun': 'notebook,curtain,luggage,helmet,castle,warehouse,pebble,shovel,visa,lighthouse,carpet,harbour',
    'adjective': 'narrow,steady,stubborn,rough,reluctant,unpleasant,dim,bitter,valid,rural,loose,shallow',
    'adverb': 'rarely,meanwhile,roughly,upstairs,suddenly,frequently,quietly,partly,consequently,instead,seldom,closely',
    'phrasal_verb': 'call off,come across,draw up,fend off,keep up with,run out of,see through,show up,sort out,step down,take over,wind up',
    'idiom': 'hit the nail on the head,under the weather,by the book,at a loss,cost an arm and a leg,bite the bullet,on the fence,in hot water,cut corners,make ends meet,once in a blue moon,go the extra mile',
    'colloquial': 'hangout,stoned,snarky,mate,blimey,cheers,bummer,hangry,sleazy,flaky,dodgy,wonky',
    'academic': 'methodology,framework,criterion,paradigm,discourse,synthesis,replication,validity,thesis,dissemination,qualitative,empirical',
    'science': 'plasma,enzyme,isotope,osmosis,viscosity,entropy,catalyst,antibody,genome,spectrum,refraction,kinetics',
    'business': 'invoice,turnover,dividend,liability,tariff,subsidy,wholesale,revenue,asset,procurement,stakeholder,overhead',
    'computing': 'recursion,cache,thread,concurrency,deadlock,checksum,tokenization,buffer,compilation,repository,throughput,kernel',
    'mathematics': 'matrix,derivative,integral,sequence,vector,topology,quotient,polynomial,symmetry,divisor,permutation,denominator',
    'statistics': 'median,quantile,probability,likelihood,distribution,correlation,confounder,skewness,kurtosis,covariance,estimator,residual',
    'machine_learning': 'overfitting,underfitting,embedding,backpropagation,regularization,hyperparameter,classification,clustering,dataset,neural network,decision tree,loss function',
    'reverse': 'вероятность,ограничение,доказательство,отношение,оценка,обучение,разработка,предположение,выборка,распределение,смещение,доступ',
    'inflected': 'went,taken,driven,frozen,children,mice,feet,men,written,thought,taught,tries',
    'spelling_variant': 'colour,behaviour,centre,neighbour,travelling,organisation,defence,labour,favourite,programme,aluminium,catalogue',
}

# Small sense-level smoke set with a reference answer chosen independently of the package.
# These alternatives are intentionally incomplete.
REFERENCE = {
    'bread': ('n',{'хлеб'}), 'door': ('n',{'дверь'}), 'window': ('n',{'окно'}),
    'visa': ('n',{'виза'}),
    'вероятность': ('n',{'probability','likelihood'}), 'доказательство': ('n',{'proof','evidence'}),
    'ограничение': ('n',{'restriction','limitation','constraint'}), 'распределение': ('n',{'distribution'}),
}


def probe_set():
    development = {normalize(query) for words in DEVELOPMENT.values() for query in words.split(',')}
    seen = set()
    for category,words in HOLDOUT.items():
        for text in words.split(','):
            key = normalize(text)
            if key in development or key in seen:
                raise ValueError('held-out query overlap')
            seen.add(key)
            yield category,text


def reference_at_cutoff(db, text, reverse, hits):
    expected = REFERENCE.get(text)
    if expected is None:
        return None
    part, equivalents = expected
    for _,sense,headword,pos in hits[:10]:
        if pos != part:
            continue
        if reverse and normalize(headword) in equivalents:
            return True
        if not reverse and any(key in equivalents for key, in db.execute(
                'SELECT search_key FROM translation WHERE sense_id=?',(sense,))):
            return True
    return False


def evaluate(db):
    queries = []
    for category,text in probe_set():
        reverse = category == 'reverse'
        key = normalize(text)
        exact = search(db,key,reverse,True)
        prefix = search(db,key,reverse,False)
        queries.append(dict(category=category,query=text,exact=bool(exact),prefix=bool(prefix),
                            reference_top_10=reference_at_cutoff(db,text,reverse,prefix)))
    return queries


def summarize(queries):
    categories = {}
    for row in queries:
        bucket = categories.setdefault(row['category'],dict(total=0,exact=0,prefix=0,reference_total=0,reference_found=0))
        bucket['total'] += 1
        bucket['exact'] += row['exact']
        bucket['prefix'] += row['prefix']
        reference = row['reference_top_10']
        if reference is not None:
            bucket['reference_total'] += 1
            bucket['reference_found'] += reference
    total = {key:sum(group[key] for group in categories.values()) for key in next(iter(categories.values()))}
    return dict(total=total,categories=categories)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pairs',type=Path,nargs='+')
    args = parser.parse_args()
    for pair in args.pairs:
        if not pair.resolve().is_relative_to((DATA/'processed').resolve()):
            parser.error('use local pairs under research/data/processed')
    results = []
    for pair in args.pairs:
        manifest = validate_research_pair(pair)
        with connect(pair/'dictionary.sqlite','ro') as db:
            results.append(dict(corpus=manifest['corpus_version'],
                manifest_sha256=checksum(pair/'manifest.json'),**summarize(evaluate(db))))
    print(json.dumps(results,indent=2))


if __name__ == '__main__':
    main()
