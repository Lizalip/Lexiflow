"""Build ignored editorial files from existing, read-only staging snapshots."""
import argparse
import csv
import hashlib
import html
import json
import sqlite3
from collections import Counter, defaultdict
from pathlib import Path

from diagnostics import diagnostic_spellings
from prototype import normalize
from release_package import connect
from review_queue import GLOSS_ROWS, IDENTITY, STRUCTURED_SOURCE, classify, csv_cell, definition_evidence

DATA = Path(__file__).resolve().parents[2]/'data'
SEED = 'lexiflow-multiword-v1'


def load_pilot(db, path):
    db.row_factory = sqlite3.Row
    with path.open(encoding='utf-8-sig',newline='') as stream:
        for original in csv.DictReader(stream):
            keys = tuple(original[key] for key in IDENTITY)
            row = db.execute('SELECT * FROM ('+GLOSS_ROWS+') WHERE entry_id=? AND sense_id=? '
                             'AND snapshot_sha256=? AND locator=?',keys).fetchone()
            if row is None or row['source'] != STRUCTURED_SOURCE:
                raise ValueError('editorial pilot key does not match FreeDict staging')
            task = dict(row)
            context = definition_evidence(db,task)
            task['definition_context'] = [item['text'] for item in context]
            ranked = classify(task)
            yield {**dict(zip(IDENTITY,keys)), 'headword':row['headword'],'pos':row['pos'],
                   'candidate':row['gloss'],'source':row['source'],'translation_rights':row['rights'],
                   'context':context,'alternatives':row['gloss_count'],'priority':ranked['review_priority'],
                   'reason':ranked['reason'],'status':'pending'}


def write_csv(path, rows):
    with path.open('x',encoding='utf-8-sig',newline='') as stream:
        writer = csv.DictWriter(stream,fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows({key:csv_cell(value) for key,value in row.items()} for row in rows)


def write_html(path, rows):
    cards = []
    for index,row in enumerate(rows):
        candidate = html.escape(row['candidate'])
        context = ''.join(
            '<p>'+html.escape(item['text'])+'<br><small>'+html.escape(
                f"{item['sense_id']} | {item['locator']} | rights {item['rights']}")+'</small></p>'
            for item in row['context'])
        detail = html.escape(f"{row['source']} | {row['locator']} | rights {row['translation_rights']}"
                             f" | candidates {row['alternatives']} | {row['priority']} — {row['reason']}")
        cards.append(f"""<article data-index="{index}"><h2>{html.escape(row['headword'])} ·
 {html.escape(row['pos'] or '—')}</h2><p>{candidate}</p>{context}<p><small>{detail}</small></p>
 <label>Решение <select><option value="">Ожидает решения</option>
 <option value="candidate_translation">Предложить перевод</option>
 <option value="unsuitable">Предложить отклонение</option></select></label>
 <label>Перевод <input value="{html.escape(row['candidate'],quote=True)}"></label>
 <label>Комментарий <textarea></textarea></label></article>""")
    keys = [{key:row[key] for key in IDENTITY} for row in rows]
    embedded = json.dumps(keys,ensure_ascii=True).replace('<','\\u003c')
    with path.open('x',encoding='utf-8') as stream:
        stream.write("""<!doctype html><html lang="ru"><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'">
<title>LexiFlow — редактура FreeDict</title><style>
body{font:16px system-ui;max-width:72rem;margin:2rem auto;padding:0 1rem}article{border-top:1px solid;padding:1rem 0}
label{display:block;margin:.5rem 0}small{overflow-wrap:anywhere}textarea,input{max-width:100%}
</style><h1>Редактура FreeDict</h1><p>Исходные кандидаты ожидают решения. Экспорт сохраняет только предложения.</p>
<label>Имя редактора <input id="reviewer" required></label><button id="export">Скачать JSONL</button>
<p id="feedback" role="status"></p>""")
        stream.write(''.join(cards))
        stream.write(f'<script type="application/json" id="identity">{embedded}</script>')
        stream.write("""<script>
const identity=JSON.parse(document.getElementById("identity").textContent);
document.getElementById("export").onclick=()=>{
  const reviewer=document.getElementById("reviewer").value.trim();
  const feedback=document.getElementById("feedback");
  const lines=[];
  if(!reviewer){feedback.textContent="Введите имя редактора";return;}
  for(const article of document.querySelectorAll("article")){
    const classification=article.querySelector("select").value;
    if(!classification)continue;
    const reason=article.querySelector("textarea").value.trim();
    const proposed=classification==="candidate_translation" ? article.querySelector("input").value.trim() : null;
    if(!reason || proposed===""){feedback.textContent="Заполните комментарий и перевод для выбранных строк";return;}
    lines.push(JSON.stringify({...identity[Number(article.dataset.index)],classification,reason,reviewer,
      proposed_translation:proposed,decision:"proposed",method:"human"}));
  }
  if(!lines.length){feedback.textContent="Выберите хотя бы одно предложение";return;}
  const url=URL.createObjectURL(new Blob([lines.join("\\n")+"\\n"],{type:"application/x-ndjson"}));
  const link=document.createElement("a");
  link.href=url;link.download="lexiflow-proposals.jsonl";link.click();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
  feedback.textContent="Экспортировано предложений: "+lines.length;
};
</script></html>""")


def multiword(databases, quota=12):
    groups = defaultdict(dict)
    diagnostics = {normalize(word) for word in diagnostic_spellings()}
    coverage = defaultdict(set)
    for db in databases:
        db.row_factory = sqlite3.Row
        query = """SELECT * FROM ("""+GLOSS_ROWS+""") WHERE instr(trim(headword),' ')>0 ORDER BY locator"""
        for row in db.execute(query):
            key = normalize(row['headword'])
            source, pos = row['source'], row['pos']
            stratum = ('source-phraseology' if pos == 'phraseologicalUnit'
                       else 'multiword-verb-review' if pos in ('v','verb')
                       else 'source-phrase' if pos in ('phrase','proverb') else 'other-multiword')
            if key in diagnostics:
                coverage[source].add(key)
            rank = hashlib.sha256(f"{SEED}/{row['snapshot_sha256']}/{row['locator']}".encode()).digest()
            group = groups[source,stratum]
            if key not in group or rank < group[key][0]:
                group[key] = rank,{key:row[key] for key in (*IDENTITY,'headword','pos','gloss','source','rights')}
    rows = [dict(row,stratum=stratum,status='pending')
            for (_,stratum),group in sorted(groups.items()) for _,row in sorted(group.values())[:quota]]
    return rows,dict(multiword_diagnostic_total=sum(' ' in key for key in diagnostics),
                     diagnostic_total=len(diagnostics),candidate_hits={s:len(keys) for s,keys in coverage.items()},
                     selected=Counter(row['stratum'] for row in rows),seed=SEED)


def diagnostic_alignment(free, kaikki, quota=8):
    """Suggest pairs on diagnostic entries only. Neither snapshot supplies a merge key."""
    mapped_pos = {'n':'noun','v':'verb','phraseologicalUnit':'phrase','pn':'name'}
    groups = defaultdict(list)
    counts = Counter()
    query = """
        SELECT s.id AS sense_id,s.position,e.spelling,e.pos,r.sha256,ev.locator,
               (SELECT count(*) FROM example x WHERE x.sense_id=s.id) AS examples,
               EXISTS(SELECT 1 FROM pronunciation p WHERE p.entry_id=e.id AND p.ipa IS NOT NULL) AS ipa
        FROM entry e JOIN sense s ON s.entry_id=e.id JOIN evidence ev ON ev.id=s.evidence_id
        JOIN release r ON r.id=ev.release_id WHERE e.lang='en' AND e.search_key=?
        ORDER BY s.id
    """

    def profile(db, key):
        db.row_factory = sqlite3.Row
        rows = []
        for row in db.execute(query,(key,)):
            item = dict(row)
            item['pos'] = mapped_pos.get(item['pos'],item['pos'])
            item['glosses'] = {normalize(text) for text, in db.execute(
                "SELECT body FROM gloss WHERE sense_id=? AND lang='ru' AND role='source_gloss'",(item['sense_id'],))}
            rows.append(item)
        return rows

    for key in sorted({normalize(word) for word in diagnostic_spellings()}):
        source = profile(free,key)
        comparison = profile(kaikki,key)
        if not source or not comparison:
            continue
        counts['shared_diagnostic_spellings'] += 1
        for left in source:
            if not left['glosses']:
                continue
            peers = [right for right in comparison if left['pos'] and left['pos'] == right['pos']]
            for right in peers:
                overlap = bool(left['glosses'] & right['glosses'])
                metadata = (left['examples'] == 0 and right['examples'] > 0) or (not left['ipa'] and right['ipa'])
                if not overlap and not metadata:
                    continue
                group = ('shared-russian-text' if overlap else 'same-pos-metadata-only',
                         'ambiguous' if len(peers)>1 else 'one-pos-candidate')
                counts['/'.join(group)] += 1
                item = dict(spelling=left['spelling'],pos=left['pos'],free_sense=left['sense_id'],
                            free_snapshot=left['sha256'],free_locator=left['locator'],
                            kaikki_sense=right['sense_id'],kaikki_snapshot=right['sha256'],
                            kaikki_locator=right['locator'],free_position=left['position'],kaikki_position=right['position'],
                            russian_text_overlap=overlap,other_same_pos=len(peers),
                            example_candidate=left['examples']==0 and right['examples']>0,
                            ipa_candidate=not left['ipa'] and bool(right['ipa']),status='pending')
                priority = hashlib.sha256(f"{SEED}/{left['locator']}/{right['locator']}".encode()).digest()
                groups[group].append((not metadata,priority,item))
    return [item for group in sorted(groups) for _,_,item in sorted(groups[group],key=lambda row:row[:2])[:quota]],dict(counts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('freedict',type=Path)
    parser.add_argument('kaikki',type=Path)
    parser.add_argument('pilot',type=Path)
    parser.add_argument('output',type=Path)
    args = parser.parse_args()
    if not args.output.resolve().is_relative_to((DATA/'review').resolve()):
        parser.error('output must be under research/data/review/')
    with connect(args.freedict,'ro') as free, connect(args.kaikki,'ro') as kaikki:
        pilot = list(load_pilot(free,args.pilot))
        phrases, stats = multiword([free,kaikki])
        alignment, ambiguity = diagnostic_alignment(free,kaikki)
        if len(pilot) != 200 or len({normalize(row['headword']) for row in pilot}) != 200 or not phrases:
            raise ValueError('expected the original 200-spelling pilot and a nonempty multiword queue')
    args.output.mkdir()
    write_csv(args.output/'freedict-context.csv',pilot)
    write_html(args.output/'freedict-review.html',pilot)
    write_csv(args.output/'multiword-review.csv',phrases)
    if alignment:
        write_csv(args.output/'metadata-links.csv',alignment)
    print(json.dumps(dict(pilot_rows=len(pilot),alignment_rows=len(alignment),ambiguity=ambiguity,**stats),
                     ensure_ascii=True))


if __name__ == '__main__':
    main()
