-- Experimental schema only. Never ship this database as an Android asset.
PRAGMA foreign_keys = ON;
CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE language (tag TEXT PRIMARY KEY NOT NULL CHECK(length(tag)>0));
CREATE TABLE source (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
CREATE TABLE release (
    id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL REFERENCES source(id),
    sha256 TEXT NOT NULL CHECK(length(sha256)=64), version TEXT NOT NULL,
    UNIQUE(source_id, sha256)
);
CREATE TABLE evidence (
    id INTEGER PRIMARY KEY, release_id INTEGER NOT NULL REFERENCES release(id),
    locator TEXT NOT NULL, external_id TEXT, rights TEXT NOT NULL DEFAULT 'pending'
        CHECK(rights IN ('pending','excluded','cleared')),
    review_note TEXT, CHECK(rights!='cleared' OR (review_note IS NOT NULL AND length(trim(review_note))>0)),
    UNIQUE(release_id, locator)
);
CREATE INDEX evidence_external ON evidence(release_id, external_id);
CREATE TABLE entry (
    id TEXT PRIMARY KEY NOT NULL, lang TEXT NOT NULL REFERENCES language(tag),
    spelling TEXT NOT NULL CHECK(length(trim(spelling))>0), search_key TEXT NOT NULL CHECK(length(search_key)>0),
    pos TEXT, evidence_id INTEGER NOT NULL UNIQUE REFERENCES evidence(id)
);
CREATE INDEX entry_search ON entry(lang, search_key, id);
CREATE TABLE sense (
    id TEXT PRIMARY KEY NOT NULL, entry_id TEXT NOT NULL REFERENCES entry(id),
    position INTEGER NOT NULL CHECK(position>0), evidence_id INTEGER NOT NULL UNIQUE REFERENCES evidence(id),
    raw_tags TEXT NOT NULL DEFAULT '[]', UNIQUE(entry_id, position)
);
CREATE TABLE gloss (
    id INTEGER PRIMARY KEY, sense_id TEXT NOT NULL REFERENCES sense(id),
    lang TEXT NOT NULL REFERENCES language(tag), body TEXT NOT NULL CHECK(length(trim(body))>0),
    role TEXT NOT NULL CHECK(role IN ('source_gloss','definition')),
    position INTEGER NOT NULL CHECK(position>0), evidence_id INTEGER NOT NULL UNIQUE REFERENCES evidence(id),
    UNIQUE(sense_id,lang,role,position)
);
CREATE TABLE translation (
    id INTEGER PRIMARY KEY, sense_id TEXT NOT NULL REFERENCES sense(id),
    lang TEXT NOT NULL REFERENCES language(tag), wording TEXT NOT NULL CHECK(length(trim(wording))>0),
    search_key TEXT NOT NULL CHECK(length(search_key)>0),
    evidence_id INTEGER NOT NULL UNIQUE REFERENCES evidence(id)
);
CREATE INDEX translation_search ON translation(lang, search_key, sense_id);
CREATE INDEX translation_sense ON translation(sense_id);
CREATE TABLE example (
    id INTEGER PRIMARY KEY, sense_id TEXT NOT NULL REFERENCES sense(id),
    lang TEXT NOT NULL REFERENCES language(tag), body TEXT NOT NULL CHECK(length(trim(body))>0),
    citation TEXT, evidence_id INTEGER NOT NULL UNIQUE REFERENCES evidence(id)
);
CREATE INDEX example_sense ON example(sense_id);
CREATE TABLE example_translation (
    id INTEGER PRIMARY KEY, example_id INTEGER NOT NULL REFERENCES example(id),
    lang TEXT NOT NULL REFERENCES language(tag), body TEXT NOT NULL CHECK(length(trim(body))>0),
    evidence_id INTEGER NOT NULL UNIQUE REFERENCES evidence(id)
);
CREATE INDEX example_translation_example ON example_translation(example_id);
CREATE TABLE pronunciation (
    id INTEGER PRIMARY KEY, entry_id TEXT NOT NULL REFERENCES entry(id), ipa TEXT,
    raw_metadata TEXT NOT NULL, evidence_id INTEGER NOT NULL UNIQUE REFERENCES evidence(id)
);
CREATE INDEX pronunciation_entry ON pronunciation(entry_id);
CREATE TABLE form (
    id INTEGER PRIMARY KEY, entry_id TEXT NOT NULL REFERENCES entry(id),
    spelling TEXT NOT NULL, search_key TEXT NOT NULL, raw_metadata TEXT NOT NULL,
    evidence_id INTEGER NOT NULL UNIQUE REFERENCES evidence(id)
);
CREATE INDEX form_entry ON form(entry_id);
CREATE INDEX form_search ON form(search_key,entry_id);
CREATE TABLE issue (
    id INTEGER PRIMARY KEY, release_id INTEGER NOT NULL REFERENCES release(id),
    locator TEXT NOT NULL, code TEXT NOT NULL, severity TEXT NOT NULL CHECK(severity IN ('review','rejected'))
);
CREATE INDEX issue_release ON issue(release_id);
