"""Bounded streaming adapter for English extraction from Russian Wiktionary.

Every raw element remains pending; no verified lexical translations are created.
This module is for local experimental analysis, not publication or Android use.
"""
from __future__ import annotations
import hashlib
import json
import math
import os
import random
import sqlite3
from collections import Counter
from itertools import islice
from pathlib import Path
from typing import BinaryIO
from prototype import NORMALIZATION_VERSION, normalize, snapshot_id, integrity

SOURCE = 'Kaikki / Russian Wiktionary / English'
SOURCE_URL = 'https://kaikki.org/ruwiktionary/Английский/index.html'
ADAPTER_VERSION = 'ruwiktionary-en-v2'


def text(value: object) -> str | None:
    if not isinstance(value,str):
        return None
    try:
        return value if normalize(value) else None
    except ValueError:
        return None


def json_text(value: object) -> str:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(',',':'), allow_nan=False)


def bad_constant(value: str) -> None:
    raise ValueError('nonstandard JSON constant')


def finite_float(value: str) -> float:
    result = float(value)
    if not math.isfinite(result):
        raise ValueError('nonfinite JSON number')
    return result


def decode_record(raw: bytes) -> object:
    return json.loads(raw.decode('utf-8'), parse_constant=bad_constant, parse_float=finite_float)


def sample_lines(stream: BinaryIO, limit: int, method: str, seed: int, targets: set[str], stats: Counter):
    lines = enumerate(stream, 1)
    if method == 'first':
        for row in islice(lines, limit):
            stats['lines_scanned'] += 1
            yield row
        return
    rng = random.Random(seed)
    reservoir = []
    for number, raw in lines:
        stats['lines_scanned'] += 1
        if method == 'random':
            slot = number - 1 if number <= limit else rng.randrange(number)
            if number <= limit:
                reservoir.append((number, raw))
            elif slot < limit:
                reservoir[slot] = (number, raw)
            continue
        try:
            record = decode_record(raw)
        except (ValueError, RecursionError):
            stats['scan_invalid_json'] += 1
            continue
        if (isinstance(record, dict) and record.get('lang_code') == 'en'
                and isinstance(record.get('word'), str) and record['word'] in targets):
            stats['target:'+record['word']] += 1
            stats['target_matches'] += 1
            if stats['target_matches'] <= limit:
                yield number, raw
    yield from sorted(reservoir)


def digest_file(stream: BinaryIO) -> str:
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024*1024), b''):
        digest.update(chunk)
    return digest.hexdigest()


def import_sample(conn: sqlite3.Connection, input_path: Path, *, limit: int = 100,
                  method: str = 'first', seed: int = 0, targets: set[str] | None = None,
                  source_version: str | None = None) -> dict:
    """Import first N physical lines to a NEW staging DB; repeat into a fresh DB.

    Existing database or file is never overwritten. SHA-256 covers the full snapshot.
    The sample is sequential and not statistically representative.
    """
    if not isinstance(limit,int) or isinstance(limit,bool) or not 1 <= limit <= 1000:
        raise ValueError('experimental limit must be 1..1000')
    if method not in ('first', 'random', 'exact') or (method == 'exact') != bool(targets):
        raise ValueError('exact sampling requires nonempty targets')
    if conn.execute('SELECT count(*) FROM release').fetchone()[0]:
        raise ValueError('use a fresh experimental database')
    stats: Counter = Counter()
    spellings: set[str] = set()
    gloss_words: set[str] = set()
    example_words: set[str] = set()
    translated_words: set[str] = set()
    ipa_words: set[str] = set()
    audio_words: set[str] = set()
    ids: Counter = Counter()
    with input_path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        digest = digest_file(stream)
        stream.seek(0)
        manifest = dict(source_url=SOURCE_URL, snapshot_sha256=digest, size_bytes=before.st_size,
                        source_version=source_version, adapter_version=ADAPTER_VERSION,
                        normalization_version=NORMALIZATION_VERSION, sample_limit=limit,
                        sample_method=method, seed=seed if method == 'random' else None,
                        exact_target_count=len(targets or ()))
        if targets:
            manifest['target_config_sha256'] = hashlib.sha256(json_text(sorted(targets)).encode()).hexdigest()
        with conn:
            source_id = conn.execute('INSERT INTO source(name) VALUES (?)',(SOURCE,)).lastrowid
            release_id = conn.execute('INSERT INTO release(source_id,sha256,version) VALUES (?,?,?)',(source_id,digest,source_version or 'unknown')).lastrowid
            conn.execute("INSERT INTO metadata VALUES ('import_manifest',?)", (json_text(manifest),))
            def issue(loc: str, code: str, severity: str = 'review') -> None:
                stats['issues_'+severity] += 1
                conn.execute('INSERT INTO issue(release_id,locator,code,severity) VALUES (?,?,?,?)',(release_id,loc,code,severity))
            def evidence(loc: str, external: object = None) -> int:
                return conn.execute('INSERT INTO evidence(release_id,locator,external_id) VALUES (?,?,?)',(release_id,loc,text(external))).lastrowid
            def items(obj: dict, field: str, loc: str) -> list:
                v = obj.get(field)
                if v is None:
                    return []
                if not isinstance(v,list):
                    issue(loc+'/'+field,'expected_array'); return []
                return v
            for line_no, raw in sample_lines(stream, limit, method, seed, targets or set(), stats):
                stats['lines_read'] += 1
                root = f'line/{line_no}'
                try:
                    obj = decode_record(raw)
                except (ValueError, UnicodeError, RecursionError):
                    stats['rejected_records'] += 1; issue(root,'invalid_json','rejected'); continue
                if not isinstance(obj,dict) or not text(obj.get('word')) or obj.get('lang_code') != 'en':
                    stats['rejected_records'] += 1; issue(root,'invalid_entry_or_language','rejected'); continue
                word = obj['word']; spellings.add(word)
                entry_id = snapshot_id(SOURCE,digest,root)
                conn.execute('INSERT INTO entry VALUES (?,?,?,?,?,?)',(entry_id,'en',word,normalize(word),text(obj.get('pos')),evidence(root,obj.get('id'))))
                stats['accepted_records'] += 1
                record_issues_before = stats['issues_review']
                if not text(obj.get('pos')):
                    issue(root+'/pos','missing_pos')
                senses = items(obj,'senses',root)
                valid_senses = 0
                usable_gloss = False
                for si, sense in enumerate(senses):
                    sl = f'{root}/senses/{si}'
                    if not isinstance(sense,dict):
                        issue(sl,'invalid_sense'); continue
                    valid_senses += 1; stats['senses'] += 1
                    external = text(sense.get('id'))
                    if external:
                        ids[external] += 1
                    else:
                        stats['missing_sense_ids'] += 1
                    sid = snapshot_id(SOURCE,digest,sl)
                    tags = {k:items(sense,k,sl) for k in ('tags','raw_tags')}
                    if any(not text(tag) for values in tags.values() for tag in values):
                        issue(sl,'invalid_tag')
                    if 'form-of' in tags['tags'] or sense.get('form_of'):
                        issue(sl,'form_reference')
                        stats['form_reference_senses'] += 1
                    conn.execute('INSERT INTO sense VALUES (?,?,?,?,?)',(sid,entry_id,si+1,evidence(sl,external),json_text(tags)))
                    has_gloss = has_example = has_translation = False
                    for gi,g in enumerate(items(sense,'glosses',sl)):
                        gl = f'{sl}/glosses/{gi}'
                        if not text(g):
                            issue(gl,'invalid_gloss'); continue
                        # Source context indicates Russian; language remains unverified.
                        conn.execute('INSERT INTO gloss(sense_id,lang,body,role,position,evidence_id) VALUES (?,?,?,?,?,?)',(sid,'ru',g,'source_gloss',gi+1,evidence(gl)))
                        has_gloss = True
                    for ei, ex in enumerate(items(sense,'examples',sl)):
                        el = f'{sl}/examples/{ei}'
                        if not isinstance(ex,dict) or not text(ex.get('text')):
                            issue(el,'invalid_example'); continue
                        eid = conn.execute('INSERT INTO example(sense_id,lang,body,citation,evidence_id) VALUES (?,?,?,?,?)',(sid,'en',ex['text'],text(ex.get('ref')),evidence(el))).lastrowid
                        has_example = True
                        if text(ex.get('translation')):
                            conn.execute('INSERT INTO example_translation(example_id,lang,body,evidence_id) VALUES (?,?,?,?)',(eid,'ru',ex['translation'],evidence(el+'/translation')))
                            has_translation = True
                        elif ex.get('translation') is not None:
                            issue(el+'/translation','invalid_example_translation')
                    if has_gloss:
                        stats['senses_with_gloss'] += 1
                        gloss_words.add(word)
                        usable_gloss = True
                    if has_example: stats['senses_with_example'] += 1
                    if has_translation: stats['senses_with_translated_example'] += 1
                    if has_gloss and has_example: example_words.add(word)
                    if has_gloss and has_translation: translated_words.add(word)
                    if not has_gloss:
                        issue(sl,'missing_gloss')
                        stats['incomplete_senses'] += 1
                if not valid_senses: issue(root,'missing_senses')
                if not usable_gloss: stats['incomplete_records'] += 1
                record_ipa = record_audio = False
                for i,sound in enumerate(items(obj,'sounds',root)):
                    loc = f'{root}/sounds/{i}'
                    if not isinstance(sound,dict): issue(loc,'invalid_sound'); continue
                    ipa = text(sound.get('ipa'))
                    for field in ('ipa', 'audio'):
                        if field in sound and not text(sound[field]):
                            issue(loc+'/'+field,'invalid_sound_field')
                    if ipa: record_ipa = True
                    if text(sound.get('audio')): record_audio = True
                    conn.execute('INSERT INTO pronunciation(entry_id,ipa,raw_metadata,evidence_id) VALUES (?,?,?,?)',(entry_id,ipa,json_text(sound),evidence(loc)))
                for i,form in enumerate(items(obj,'forms',root)):
                    loc = f'{root}/forms/{i}'
                    if not isinstance(form,dict) or not text(form.get('form')): issue(loc,'invalid_form'); continue
                    conn.execute('INSERT INTO form(entry_id,spelling,search_key,raw_metadata,evidence_id) VALUES (?,?,?,?,?)',(entry_id,form['form'],normalize(form['form']),json_text(form),evidence(loc)))
                if record_ipa: stats['records_with_ipa'] += 1; ipa_words.add(word)
                if record_audio: stats['records_with_audio'] += 1; audio_words.add(word)
                if stats['issues_review'] > record_issues_before: stats['records_requiring_structural_review'] += 1
            after = os.fstat(stream.fileno())
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise ValueError('source changed during import')
            integrity(conn)
        # No raw text or source IDs in the aggregate report.
    keys = ['lines_read','accepted_records','rejected_records','senses','missing_sense_ids','senses_with_gloss','senses_with_example','senses_with_translated_example','records_with_ipa','records_with_audio','records_requiring_structural_review','issues_review','issues_rejected']
    report = {k:stats[k] for k in keys}
    report.update(snapshot_sha256=digest, sample_limit=limit, sample_method='first physical JSONL lines; not representative', unique_spellings=len(spellings),unique_spellings_with_gloss=len(gloss_words),unique_spellings_with_gloss_and_example=len(example_words),unique_spellings_with_gloss_and_translated_example=len(translated_words),unique_spellings_with_ipa=len(ipa_words),unique_spellings_with_audio=len(audio_words),duplicate_source_id_groups=sum(v>1 for v in ids.values()),duplicate_source_id_excess=sum(v-1 for v in ids.values()), records_with_unresolved_rights=stats['accepted_records'],verified_lexical_translations=0,production_ready=False)
    report.update(manifest)
    for key in ('lines_scanned', 'scan_invalid_json', 'target_matches', 'incomplete_records',
                'incomplete_senses', 'form_reference_senses'):
        report[key] = stats[key]
    report['pending_evidence'] = conn.execute("SELECT count(*) FROM evidence WHERE rights='pending'").fetchone()[0]
    report['matched_target_spellings'] = sum(bool(stats['target:'+target]) for target in targets or ())
    report['truncated_target_records'] = max(0, stats['target_matches'] - limit)
    return report
