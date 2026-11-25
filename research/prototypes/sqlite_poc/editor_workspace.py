"""Build ignored editorial files from existing, read-only staging snapshots."""
import argparse
import csv
import hashlib
import html
import json
import re
import sqlite3
from collections import Counter, defaultdict
from pathlib import Path

from diagnostics import diagnostic_spellings
from candidate_rank import subtlex_frequency
from prototype import normalize
from release_package import checksum, connect, readiness
from review_queue import GLOSS_ROWS, IDENTITY, STRUCTURED_SOURCE, classify, csv_cell, definition_evidence

DATA = Path(__file__).resolve().parents[2]/'data'
SEED = 'lexiflow-multiword-v1'
FUNCTION_WORDS = frozenset('a an the of to for with at by in on from as and or but if than it '
                           'he she they we you not no can may will would should that which who'.split())


def review_signals(task, context, priority):
    """Flag review questions. Surface markers cannot establish semantic errors."""
    raw = json.loads(task['raw_tags'])
    labels = raw.get('labels',[]) if isinstance(raw,dict) else []
    issues = []
    if task['gloss_count'] > 1:
        issues.append('multiple-source-alternatives')
    if len(context) != 1:
        issues.append('unclear-context')
    if task['pos'] in ('pn','name'):
        issues.append('proper-name')
    if labels:
        issues.append('source-style-or-region-label')
    word = task['headword']
    if not re.fullmatch(r"[A-Za-z]+(?:[ ’'\-][A-Za-z]+)*",word):
        issues.append('spelling-needs-check')
    if task['pos'] in ('n','noun') and context and context[0]['text'].casefold().startswith('to '):
        issues.append('pos-and-definition-signal')
    if priority == 'exclude':
        issues.append('excluded-by-structural-triage')
    return issues


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
            issues = review_signals(task,context,ranked['review_priority'])
            alternatives = [value for value, in db.execute(
                "SELECT body FROM gloss WHERE sense_id=? AND role='source_gloss' ORDER BY position,id",
                (row['sense_id'],))]
            yield {**dict(zip(IDENTITY,keys)), 'headword':row['headword'],'pos':row['pos'],
                   'candidate':row['gloss'],'source':row['source'],'translation_rights':row['rights'],
                   'context':context,'alternatives':row['gloss_count'],'priority':ranked['review_priority'],
                   'source_alternatives':alternatives,'source_url':original.get('source_url',''),
                   'license_id':original.get('license_id',''),'terms_url':original.get('terms_url',''),
                   'attribution_note':original.get('source',''),
                   'audit_priority':'specialist' if issues and ranked['review_priority']=='standard' else ranked['review_priority'],
                   'issues':issues,'reason':ranked['reason'],'status':'pending'}


def write_csv(path, rows):
    with path.open('x',encoding='utf-8-sig',newline='') as stream:
        writer = csv.DictWriter(stream,fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows({key:csv_cell(value) for key,value in row.items()} for row in rows)


def write_html(path, rows):
    priorities = {'specialist':'Сложные задачи','standard':'Стандартные кандидаты','exclude':'Исключённые эвристикой'}
    rows = sorted(rows,key=lambda row:list(priorities).index(row.get('audit_priority',row['priority'])))
    cards = []
    previous = None
    for index,row in enumerate(rows):
        priority = row.get('audit_priority',row['priority'])
        if priority != previous:
            if previous is not None: cards.append('</section>')
            cards.append(f'<section><h2>{priorities[priority]}</h2>')
            previous = priority
        candidate = html.escape(row['candidate'])
        context = ''.join(
            '<p>'+html.escape(item['text'])+'<br><small>'+html.escape(
                f"{item['sense_id']} | {item['locator']} | rights {item['rights']}")+'</small></p>'
            for item in row['context'])
        detail = html.escape(f"{row['source']} | {row['locator']} | rights {row['translation_rights']}"
                             f" | candidates {row['alternatives']} | {row['priority']} — {row['reason']}")
        provenance = html.escape(' | '.join(row.get(key,'') for key in ('source_url','license_id','terms_url')))
        alternatives = ' · '.join(html.escape(value) for value in row.get('source_alternatives',[]))
        issues = ', '.join(html.escape(reason) for reason in row.get('issues',[]))
        cards.append(f"""<article data-index="{index}" data-priority="{priority}"><h3>{html.escape(row['headword'])} ·
 {html.escape(row['pos'] or '—')}</h3><p>Исходный перевод: <strong>{candidate}</strong></p>{context}
 <p><small>{detail}<br>Sense ID: {html.escape(row['sense_id'])}<br>{provenance}<br>
 Варианты: {alternatives or candidate}<br>Сигналы: {issues or '—'}</small></p>
 <label>Черновик решения <select><option value="">Ожидает решения</option>
 <option value="candidate_translation">Предложить перевод</option>
 <option value="unsuitable">Предложить отклонение</option></select></label>
 <label>Предлагаемый перевод <input maxlength="1000" value="{html.escape(row['candidate'],quote=True)}"></label>
 <label>Комментарий <textarea maxlength="2000"></textarea></label></article>""")
    if cards: cards.append('</section>')
    keys = [{key:row[key] for key in IDENTITY} for row in rows]
    embedded = json.dumps(keys,ensure_ascii=True).replace('<','\\u003c')
    with path.open('x',encoding='utf-8') as stream:
        stream.write("""<!doctype html><html lang="ru"><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'">
<title>LexiFlow — редактура FreeDict</title><style>
body{font:16px system-ui;max-width:72rem;margin:2rem auto;padding:0 1rem}article{border-top:1px solid;padding:1rem 0}
label{display:block;margin:.5rem 0}small{overflow-wrap:anywhere}textarea,input{max-width:100%}
</style><h1>Редактура FreeDict</h1><p>Исходные кандидаты ожидают решения. Экспорт сохраняет только предложения.</p>
<label>Поиск <input id="query" type="search"></label>
<label>Приоритет <select id="priority"><option value="">Все</option><option value="specialist">Сложные</option>
<option value="standard">Стандартные</option><option value="exclude">Исключённые</option></select></label>
<label>Состояние <select id="state"><option value="">Все</option><option value="pending">Без предложения</option>
<option value="draft">Есть черновик</option></select></label><p id="counts" role="status"></p>
<label>Имя редактора <input id="reviewer" maxlength="200" required></label><button id="export">Скачать предложения JSONL</button>
<button id="save-draft">Сохранить сессию</button>
<label>Восстановить сессию <input type="file" id="open-draft" accept=".json"></label>
<p id="feedback" role="status"></p>""")
        stream.write(''.join(cards))
        stream.write(f'<script type="application/json" id="identity">{embedded}</script>')
        batch = hashlib.sha256(embedded.encode()).hexdigest()
        script = Path(__file__).with_name('editor_browser.js').read_text(encoding='utf-8')
        stream.write(f'<script>const batch = "{batch}";\n{script}</script></html>')


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


def coverage_profile(db, frequency):
    common = set(sorted(frequency,key=lambda word:(-frequency[word],word))[:1000])
    spellings = {key for key, in db.execute("SELECT DISTINCT search_key FROM entry WHERE lang='en'")}
    query = """
        SELECT e.search_key,count(DISTINCT s.id),count(DISTINCT e.pos)
        FROM entry e JOIN sense s ON s.entry_id=e.id WHERE e.lang='en' GROUP BY e.search_key
    """
    senses = db.execute('SELECT count(*) FROM sense').fetchone()[0]
    profile = dict(spellings=len(spellings),top1000_subtlex_form_hits=len(common & spellings),
                   function_word_hits=len(FUNCTION_WORDS & spellings),function_word_total=len(FUNCTION_WORDS),
                   multiword_spellings=sum(' ' in key for key in spellings),senses=senses,
                   entries_with_forms=db.execute('SELECT count(DISTINCT entry_id) FROM form').fetchone()[0])
    for key, count, parts in db.execute(query):
        profile['spellings_with_5plus_sense_nodes'] = profile.get('spellings_with_5plus_sense_nodes',0)+(count>=5)
        profile['spellings_with_multiple_pos'] = profile.get('spellings_with_multiple_pos',0)+(parts>1)
    for label, sql in {
            'senses_with_source_russian_text': "SELECT count(DISTINCT sense_id) FROM gloss WHERE role='source_gloss' AND lang='ru'",
            'senses_with_definitions': "SELECT count(DISTINCT sense_id) FROM gloss WHERE role='definition'",
            'senses_with_examples': 'SELECT count(DISTINCT sense_id) FROM example',
            'entries_with_ipa': 'SELECT count(DISTINCT entry_id) FROM pronunciation WHERE ipa IS NOT NULL',
    }.items():
        profile[label] = db.execute(sql).fetchone()[0]
    return profile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('freedict',type=Path)
    parser.add_argument('kaikki',type=Path)
    parser.add_argument('pilot',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--freedict-archive',type=Path)
    parser.add_argument('--frequency-archive',type=Path)
    parser.add_argument('--frequency-staging',type=Path)
    args = parser.parse_args()
    if bool(args.frequency_archive) != bool(args.frequency_staging):
        parser.error('frequency archive and staging must be supplied together')
    if not args.output.resolve().is_relative_to((DATA/'review').resolve()):
        parser.error('output must be under research/data/review/')
    with connect(args.freedict,'ro') as free, connect(args.kaikki,'ro') as kaikki:
        pilot = list(load_pilot(free,args.pilot))
        phrases, stats = multiword([free,kaikki])
        alignment, ambiguity = diagnostic_alignment(free,kaikki)
        source = free.execute('SELECT sha256 FROM release').fetchone()[0]
        if args.freedict_archive and checksum(args.freedict_archive) != source:
            raise ValueError('FreeDict archive does not match staging')
        audit = dict(checked_pilot=len(pilot),snapshot=source,
                     priority=dict(Counter(row['audit_priority'] for row in pilot)),
                     flags=dict(Counter(flag for row in pilot for flag in row['issues'])),
                     unique_child_context=sum(len(row['context'])==1 and
                                              row['context'][0]['relationship']=='direct-child' for row in pilot),
                     release=readiness(free),human_decisions_created=0)
        if args.frequency_archive:
            with connect(args.frequency_staging,'ro') as frequent:
                manifest = json.loads(frequent.execute(
                    "SELECT value FROM metadata WHERE key='import_manifest'").fetchone()[0])
                if checksum(args.frequency_archive) != manifest['frequency_sha256']:
                    raise ValueError('frequency archive does not match selection')
                frequency = subtlex_frequency(args.frequency_archive)
                audit['coverage'] = dict(freedict=coverage_profile(free,frequency),
                                         kaikki_ranked=coverage_profile(kaikki,frequency),
                                         kaikki_frequency=coverage_profile(frequent,frequency))
        if len(pilot) != 200 or len({normalize(row['headword']) for row in pilot}) != 200 or not phrases:
            raise ValueError('expected the original 200-spelling pilot and a nonempty multiword queue')
    args.output.mkdir()
    write_csv(args.output/'freedict-context.csv',pilot)
    write_html(args.output/'freedict-review.html',pilot)
    write_csv(args.output/'multiword-review.csv',phrases)
    priority_phrases = [row for row in phrases if row['stratum'] in ('source-phraseology','multiword-verb-review')
                        and row['rights'] == 'pending']
    if priority_phrases:
        write_csv(args.output/'priority-expressions.csv',priority_phrases)
    if alignment:
        write_csv(args.output/'metadata-links.csv',alignment)
    audit['multiword'] = dict(priority_rows=len(priority_phrases),**stats)
    with (args.output/'editorial-audit.json').open('x',encoding='utf-8') as stream:
        json.dump(audit,stream,ensure_ascii=False,indent=2)
        stream.write('\n')
    print(json.dumps(dict(pilot_rows=len(pilot),alignment_rows=len(alignment),ambiguity=ambiguity,
                          audit_priority=audit['priority'],audit_flags=audit['flags'],**stats),
                     ensure_ascii=True))


if __name__ == '__main__':
    main()
