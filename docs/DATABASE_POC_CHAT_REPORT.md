# LexiFlow — Independent SQLite / ETL prototype report

**Date:** 25 November 2026

**Baseline:** `f53b6f1b` (`feature/android-foundation`)

**Status:** experimental code verified on synthetic fixtures; no production approval.

## Evidence and scope

The experiment follows the repository's `docs/DATABASE_DESIGN_DRAFT.md`, `docs/DATABASE_ERD.md`, `AGENTS.md`, `research/docs/LexiFlow_Data_Source_Audit_Report.md`, and the saved 51-cell `research/notebooks/01_source_audit.ipynb` at the baseline revision. The source audit reports 72,487 records, 67,290 distinct spellings, 104,902 senses and 104,900 distinct sense IDs; these are prior notebook results, not measurements rerun here. The raw JSONL was not available in this execution environment. No claims are made about actual import quality or linguistic accuracy.

## Implemented

An isolated staging SQLite schema (14 tables), source/release/evidence lineage, separate entry and sense, source gloss versus verified translation, usage examples with separate translations and evidence, record-level pronunciation/form metadata, structural issue log, parameterized exact/prefix lookup EN/RU, Unicode normalization vectors, bounded streaming JSONL import, aggregate statistics and non-overwriting export CLI. Uses only Python standard library. No app assets, Android changes, dependencies or existing research files are included.

## Design review: decisions to carry forward

| Area | Recommendation / status |
|---|---|
| Essential dictionary core | Keep entry, sense, source/release/evidence, gloss, explicit translation, example and example translation, language, POS and pronunciation. They preserve sense identity and provenance. |
| Essential user features for full Stage 1 | Saved entries/senses, own translations/notes, collections, cards, scheduling and events. Keep user data separate from replaceable dictionary snapshots. Not implemented in this prototype. |
| Optional / extensible | Rich normalized grammatical features, CEFR, illustration and audio metadata can be expanded after coverage and rights audit. Required user-facing behaviors in HANDOFF must not be removed merely because data are missing. |
| UUID | Snapshot-local UUID5 is a reversible experiment, not permanent user-reference identity. A durable cross-release registry with reconciliation is required; compare TEXT UUID, BLOB UUID and integer surrogate + permanent ID before approving. |
| Search | B-tree prefix range works in this desktop experiment. Preserve sense IDs and exact-before-prefix ordering. FTS is a separate alternative for richer search, not a prerequisite for prefix lookup. |
| Normalization | NFC -> casefold -> NFC -> collapse Python whitespace. Preserve ё, punctuation distinctions. Java lowercase alone is not equivalent; agree version and verify Android implementation against shared vectors before exporting production search keys. |
| Rights | Each imported evidence row is pending; no raw gloss is a verified translation. Explicit cleared fixtures demonstrate reverse lookup, not real clearance. Final license, attribution, release distribution and publishing gate still need design and approval. |
| Android | No Room integration yet. Exact entity schema, migrations, read-only dictionary packaging and user DB need separate approval and device tests. |

The staging schema intentionally uses POS text, raw JSON for tags/form/sound metadata and a minimal source/release record instead of every normalized table in the design draft. It omits the production license registry, full attribution, cross-release mapping, user DB, scheduling and multi-source alignment. Language tags of nested texts are inferred from the source context. English candidate search exposes pending entries by design and must not be reused directly as a public search API. Reverse search requires explicit translation rows and cleared parent evidence. Multiple matching translations with different wording can yield multiple hits for one sense; final result grouping requires a product decision. Indexes in this experiment cover search and major parent joins, not every FK of the final model. Form lookup is not implemented. These are explicit limits, not silent changes to the approved requirements.

## Executed tests

Environment: Python 3.13.5, SQLite 3.46.1, Unicode data 15.1.0, Linux container.

Command (from prototype directory): `python -m unittest -v`.

Result: **23/23 tests passed** (0 failures/errors). The test suite covers:

- separate senses and POS, pending evidence filtering, no implicit gloss translation, duplicate source IDs;
- exact and prefix search, empty input, parameterization, ordering and limit;
- normalization vectors (15 examples), surrogate/NUL rejection, prefix boundary, snapshot identity;
- integrity and FK checks, cleared evidence requiring a review note;
- nested malformed JSONL, malformed JSON/UTF-8/nonstandard NaN, language rejection, record limit, coverage counts, sense/example lineage;
- logical repeat-import equivalence into fresh databases; refusal to append to a populated DB;
- CLI output creation, refusal to overwrite, cleanup on failed import.

Observed `EXPLAIN QUERY PLAN` on synthetic fixtures:

```text
EN -> RU (headword candidate search)
SEARCH e USING INDEX entry_search (lang=? AND search_key>? AND search_key<?)
SEARCH ev USING INTEGER PRIMARY KEY (rowid=?)
SEARCH s USING INDEX sqlite_autoindex_sense_3 (entry_id=?) LEFT-JOIN
USE TEMP B-TREE FOR DISTINCT
USE TEMP B-TREE FOR ORDER BY

RU -> EN (verified translation lookup)
SEARCH t USING INDEX translation_search (lang=? AND search_key>? AND search_key<?)
SEARCH ev USING INTEGER PRIMARY KEY (rowid=?)
SEARCH s USING INDEX sqlite_autoindex_sense_1 (id=?)
SEARCH e USING INDEX sqlite_autoindex_entry_1 (id=?)
SEARCH se USING INTEGER PRIMARY KEY (rowid=?)
SEARCH ee USING INTEGER PRIMARY KEY (rowid=?)
USE TEMP B-TREE FOR DISTINCT
USE TEMP B-TREE FOR ORDER BY
```

Both search paths used the intended indexes. Temporary sorting/deduplication remains a performance question; no full-corpus throughput, size, or Android latency has been measured. `integrity_check` and `foreign_key_check` passed on fixtures. Real raw file: not present. Real records imported: **0**. Android build/device: not run, no Android changes. No real licenses cleared. No commit/push performed.

## Supplementary-source preparation (from the existing audit; not a new live audit)

| Candidate | Intended role | Matching level and review |
|---|---|---|
| Russian Wiktionary / Kaikki | Main bilingual candidate | Preserve parent record/sense; classify source gloss; verify item rights and snapshot. |
| English Wiktionary / Kaikki | Definitions, IPA, idioms and missing expressions | Entry/POS candidates, then sense and pronunciation review; no spelling-only merges. |
| Tatoeba | Candidate bilingual examples | Sentence and translation IDs, semantic sense match, applicable licenses and attribution. |
| CEFR-J / Octanove | Candidate levels | Entry/POS; avoid inheriting level to every sense; verify specific dataset terms. |
| FreeDict / WikDict | Reserve translations | Verify version, translation alignment, licenses and coverage. |
| Open English WordNet | Reserve definitions | Sense alignment, not spelling-only mapping. |
| Wikimedia Commons | Optional media | File-specific rights, attribution and semantic suitability. |
| Android TTS | Pronunciation fallback | Device language/voice and offline availability; not IPA source. |

## Next engineering work

1. Integrate this new directory without replacing existing files; rerun tests locally and review the code against actual notebook structures.
2. If authorized local raw source is available, perform 100 and then up to 1,000-line imports; record full SHA-256, file metadata, coverage, issues and separate pending rights. If unavailable, prepare exact local command and continue other independent tasks.
3. Add a deterministic diagnostic/sample selection mechanism for the 24 target spellings, and a local review/export queue for candidate gloss classifications. Never mark real translations or rights approved automatically.
4. Harden importer (manifest metadata, nested validation, atomicity, evidence details, rejection tracking, cross-platform behavior). Add tests.
5. Compare production schema alternatives, including stable ID and Android normalization, and propose a minimal approved data contract. No production schema or data publication until approval.
