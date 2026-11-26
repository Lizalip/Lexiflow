"""Evaluate the Android ranking SQL on desktop SQLite using fixed diagnostic queries."""
import argparse
import base64
import csv
import subprocess
from collections import Counter
from pathlib import Path
from tempfile import TemporaryDirectory

from prototype import normalize
from release_package import checksum, connect
from research_package import DATA

ROOT = Path(__file__).resolve().parents[3]
BRIDGE = """
package app.lexiflow.data;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class RankingProbe {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null;) {
            var pair = line.split("\\t",2);
            String key = new String(Base64.getDecoder().decode(pair[1]), StandardCharsets.UTF_8);
            var query = DictionarySearch.ranked(key, "ru".equals(pair[0]), true);
            System.out.print(encode(query.sql()));
            for (String value : query.arguments()) System.out.print("\\t"+encode(value));
            System.out.println();
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
"""

# The list is chosen independently of the results. Each positive target identifies
# one expected lexical article or class of articles, not all valid answers.
PROBES = (
    ('common','en','kitchen','kitchen','en',''),
    ('common','en','decide','decide','en',''),
    ('common','en','pencil','pencil','en',''),
    ('polysemy','en','head','head','en',''),
    ('polysemy','en','draw','draw','en',''),
    ('polysemy','en','bear','bear','en',''),
    ('advanced','en','scrupulous','scrupulous','en',''),
    ('advanced','en','inconspicuous','inconspicuous','en',''),
    ('advanced','en','irreconcilable','irreconcilable','en',''),
    ('academic','en','hypothesis','hypothesis','en',''),
    ('academic','en','correlation','correlation','en',''),
    ('academic','en','inference','inference','en',''),
    ('computer','en','recursion','recursion','en',''),
    ('computer','en','compiler','compiler','en',''),
    ('computer','en','hash table','hash table','en',''),
    ('data','en','regression','regression','en',''),
    ('data','en','variance','variance','en',''),
    ('data','en','training set','training set','en',''),
    ('russian','ru','деньги','деньги','ru',''),
    ('russian','ru','город','город','ru',''),
    ('russian','ru','встреча','встреча','ru',''),
    ('phrases','en','bear with','bear with','en',''),
    ('phrases','en','give in','give in','en',''),
    ('phrases','en','by the way','by the way','en',''),
    ('phrases','en','risk   assessment','risk assessment','en',''),
    ('forms','en','spoken','speak','en','spoken'),
    ('forms','en','geese','goose','en','geese'),
    ('forms','en','thought','think','en','thought'),
    ('prefix','en','penci','pencil','en',''),
    ('reverse','ru','карандаш','pencil','en',''),
    ('reverse','ru','кухня','kitchen','en',''),
    ('unicode','ru','  Е\u0308Ж  ','ёж','ru',''),
    ('unicode','ru','ЕЖ','еж','ru',''),
    ('unicode','en','  SELF-ESTEEM  ','self-esteem','en',''),
    ('unicode','en',"  CAN'T  ","can't",'en',''),
    ('literal','en','%_[]','','',''),
    ('literal','ru','[?*]','','',''),
)


def android_queries(jdk, android_jar, probes):
    source = ROOT/'app/src/main/java/app/lexiflow/data'
    with TemporaryDirectory(dir=ROOT/'.gradle',prefix='ranking-probe-') as temporary:
        path = Path(temporary)
        bridge = path/'RankingProbe.java'
        bridge.write_text(BRIDGE,encoding='utf-8')
        subprocess.run([jdk/'bin/javac','-encoding','UTF-8','-cp',android_jar,'-d',path,
                        source/'DictionarySearch.java',source/'SearchNormalization.java',bridge],check=True)
        request = '\n'.join(f'{direction}\t{base64.b64encode(normalize(query).encode()).decode()}'
                            for _,direction,query,*_ in probes)+'\n'
        output = subprocess.run([jdk/'bin/java','-cp',path,'app.lexiflow.data.RankingProbe'],
                                input=request,text=True,capture_output=True,check=True).stdout
    return [[base64.b64decode(part).decode() for part in line.split('\t')] for line in output.splitlines()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--jdk',type=Path,required=True)
    parser.add_argument('--android-jar',type=Path,required=True)
    args = parser.parse_args()
    if not all(path.resolve().is_relative_to((DATA/'processed').resolve()) for path in (args.package,args.output)):
        parser.error('use local package and output paths under research/data/processed')
    variants = android_queries(args.jdk,args.android_jar,PROBES)
    database = args.package/'dictionary.sqlite'
    digest = checksum(database)
    rows = []
    with connect(database,'ro') as db:
        for probe,(sql,*parameters) in zip(PROBES,variants,strict=True):
            group,direction,query,headword,language,expected_form = probe
            hits = db.execute(sql,parameters).fetchall()
            if len({row[1] for row in hits})!=len(hits) or len(hits)>50:
                raise ValueError('ranking repeated a sense or exceeded the result bound')
            matches = [i for i,row in enumerate(hits,1) if row[7]==language
                       and normalize(row[2])==headword and (not expected_form or row[6]==expected_form)]
            rows.append(dict(group=group,direction=direction,query=query,expected=headword,
                             matches=len(hits),expected_rank=matches[0] if matches else '',
                             expected_found=bool(matches) if headword else not hits,
                             first_word=hits[0][2] if hits else '',first_language=hits[0][7] if hits else '',
                             sqlite_sha256=digest))
    with args.output.open('x',encoding='utf-8',newline='') as output:
        writer = csv.DictWriter(output,fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    print(f"{sum(row['expected_found'] for row in rows)}/{len(rows)} diagnostics")
    print(dict(Counter(row['group'] for row in rows if row['expected_found'])))


if __name__ == '__main__':
    main()
