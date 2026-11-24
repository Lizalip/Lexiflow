# LexiFlow — Dictionary Source Audit Report
**Date:** 2026-11-24  
**Stage:** Stage 1 / data feasibility  
**Status:** Preliminary empirical audit completed; production data approval pending.

## 1. Executive decision
Recommend Kaikki's English-language extraction from Russian Wiktionary as the **primary candidate** for the bilingual lexical core, not the sole content source. It offers substantial Russian-language gloss coverage and a sense-level structure. The extraction is insufficient on its own for the project's emphasis on idioms, informal language, pronunciation and contextual examples. Proceed to a small controlled ETL prototype while the Android foundation is developed independently. Do not treat raw counts as approved, publication-ready cards.

## 2. Evidence and scope
Primary empirical evidence: `research/notebooks/01_source_audit.ipynb`, supplied with saved outputs. Source file used in the notebook: `research/data/raw/kaikki.org-dictionary-Английский.jsonl`.
Source catalog: https://kaikki.org/ruwiktionary/Английский/index.html
Notebook reports a published size of 83.0 MB and extraction date 2026-11-01; these are reported metadata, not independently verified file metadata. The raw JSONL was not supplied with the notebook; the analysis was not rerun independently. Record download URL, dump and extractor versions if available, actual byte size and SHA-256 before repeatable ingestion.

Methods: initial inspection of first 1,000 records; full DataFrame loading; top-level non-null coverage; sense-level flattening with `explode` and `json_normalize`; non-empty text tests; duplicate ID detection; pronunciation metadata and tag counts; exact-string diagnostic lookup and selected manual review.

## 3. Measured dataset size
| Metric | Value |
|---|---:|
| Top-level records | 72,487 |
| Distinct word strings (not verified lemmas) | 67,290 |
| Top-level fields | 25 |
| Sense objects | 104,902 |
| Distinct source sense IDs | 104,900 |
| Missing IDs | 0 |
| Duplicate ID occurrences beyond first | 2 |

The first 1,000 records contain 642 distinct word strings and 24 fields. They are not a random sample.

## 4. Content coverage
| Metric | Senses | % of all senses | Distinct word strings with at least one qualifying sense |
|---|---:|---:|---:|
| Non-empty gloss | 97,192 | 92.65 | 59,646 |
| Non-empty example | 22,386 | 21.34 | 17,464 (with gloss) |
| Example with non-empty translation | 20,975 | 19.99 | 16,647 (with gloss) |

For the audited snapshot, all senses counted with examples or translated examples also satisfy the non-empty gloss test. These are content availability counts, not validated translations, verified language, complete cards, or rights clearance. A Russian gloss may be a translation, explanation, form reference or a mixture. Do not manufacture an independent definition from the same gloss or claim it is a verified translation automatically.

| Record-level metric | Records | Distinct word strings | % of records |
|---|---:|---:|---:|
| Non-empty IPA | 14,980 | 11,773 | 20.67 |
| Non-empty audio reference | 4,535 | 3,268 | 6.26 |
| Non-null sounds | 15,088 | — | 20.81 |
| Non-null forms | 10,659 | — | 14.70 |
| Non-null synonyms | 6,963 | — | 9.61 |
| Non-null antonyms | 1,485 | — | 2.05 |

Do not compare these record percentages directly to sense-level percentages. Intersection of gloss, usable examples and IPA on matching records has not been measured.

## 5. Structural and semantic findings
- One word can have multiple top-level records (e.g. noun/verb). Each record has a list of senses; examples belong to senses; sounds and forms are record-level data. Preserve parent-record lineage when flattening. Never join pronunciations on word spelling alone when homographs differ.
- `glosses` is a list; `examples` contains dictionaries with text, translation, optional `ref`, and highlighting offsets.
- Two duplicate-ID groups: `as` contains distinct contexts and examples with the same source ID; preserve both. `rase` appears redundant in the displayed normalized columns; compare full original records and provenance before deduplication. Use persistent internal IDs and separate source IDs, plus version-to-version reconciliation. No blanket `drop_duplicates(subset="id")`.
- Sense tags include `no-gloss` (7,710), `form-of` (169), `slang` (4), `colloquial` (4). These are tag occurrences, not counts of unique words. Register markers may be embedded in glosses (`bloke`: Russian jargon marker), categories, or raw tags.
- Regional pronunciation labels include Russian country names, abbreviations and accent labels such as RP and GA. Preserve original labels; map only verified equivalents; separate locale, accent and grammatical number.
- References to third-party published texts occur in the examples for `bank`. Rights for original and translated quotations require separate review.

## 6. Diagnostic vocabulary assessment
| Group | Tested | Found | With gloss | With example | Exact-match coverage |
|---|---:|---:|---:|---:|---:|
| Common vocabulary | 4 | 4 | 4 | 2 | 100% |
| Polysemous words | 5 | 5 | 5 | 2 | 100% |
| Phrasal verbs | 4 | 3 | 3 | 3 | 75% |
| Idioms | 3 | 0 | 0 | 0 | 0% |
| Informal/regional vocabulary | 8 | 5 | 5 | 1 | 62.5% |
| Total | 24 | 17 | 17 | 8 | 70.83% |

Seven missing exact spellings: put off, spill the beans, break the ice, piece of cake, low-key, legit, no big deal. Alternative spellings and nested references were not tested. This targeted set is diagnostic and not representative. Seven of the 24 test strings have at least one translated example. Examples for `bank` distinguish the financial and riverbank senses; `give up` has one example across seven senses. `charge` (45 senses) and `set` (26) have no examples. No scored independent linguistic accuracy estimate was produced.

## 7. Recommended source strategy (provisional)
| Source | Role | Next action |
|---|---|---|
| Russian Wiktionary / Kaikki | Bilingual core and source glosses | Use for controlled PoC; confirm license and provenance per content type |
| English Wiktionary / Kaikki | English definitions, IPA, idioms, missing expressions and register metadata | First supplementary audit: targeted sample, then stream/filter bulk data; never match sense-level fields by spelling alone |
| Tatoeba | Candidate bilingual examples | Verify licenses and attribution; preserve sentence/translation IDs; review semantic fit, not just token match |
| CEFR-J / Octanove | Candidate vocabulary-level annotations | Verify license of each file; map by spelling and POS where supported, not all senses automatically |
| FreeDict / WikDict | Reserve for missing Russian translations | Evaluate specific dataset versions, alignment quality and licenses only if needed |
| Open English WordNet | Reserve English definitions | Consider only if English Wiktionary does not suffice; require correct sense alignment |
| Wikimedia Commons | Optional media | Per-file rights, attribution and suitability review; placeholder if unavailable |
| Android TTS | Pronunciation fallback | Test installed voices, language and offline availability; not a source of IPA |

References (candidate documentation, not measured supplementary coverage):
- https://kaikki.org/dictionary/English/
- https://en.wiktionary.org/wiki/Wiktionary:Copyrights
- https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use
- https://en.wiki.tatoeba.org/articles/show/using-the-tatoeba-corpus
- https://github.com/openlanguageprofiles/olp-en-cefrj
- https://creativecommons.org/licenses/by-sa/4.0/
- https://freedict.org/downloads/
- https://www.wikdict.com/en-ru/
- https://en-word.net/
- https://commons.wikimedia.org/

## 8. Licensing gate
Wiktextract software licensing does not license every extracted item. Wiktionary contributor text is generally available under CC BY-SA 4.0 subject to applicable conditions; third-party material can have different terms. Preserve source-page links, revision/snapshot information, attribution, license and modifications. Quarantine material whose rights are unresolved, including externally cited examples and translations; lack of a `ref` is not proof of clearance. Verify media separately. Review ShareAlike obligations and the distribution method of adapted data before release. Keep code and user data separate. This is preliminary compliance guidance, not legal clearance.

## 9. Next milestone — controlled ETL PoC (proposal, requires approval)
1. Define source manifest, input checksum and reproducible configuration. Keep full raw files outside Git; avoid publishing notebook outputs with uncleared quotations.
2. Agree a multilingual schema separating lexical entries, senses, glosses, structured translations, examples, pronunciations, forms, tags and provenance. Preserve links to original parent records and sense IDs. Keep the user's DB separate.
3. Audit English Wiktionary on the existing 24-item diagnostic list, including alternative spellings. Select a small genuine sample (e.g. 50–100 entries) for integration. Do not synthesize lexical content.
4. Build import adapters and validation: missing/empty values, form-only entries, duplicate IDs, correct languages, normalized tags, source attribution, ambiguous matches and rights status. Log rejected and quarantined items. Keep unresolved fields null.
5. Implement bilingual search prototype with indexed English headwords and validated Russian translation strings; do not index every gloss as a clean Russian translation without labeling the distinction.
6. Measure joint content coverage at matching entry/sense levels, quality and source alignment. Run reproducible random review and targeted diagnostic review; proposed acceptance criteria from handoff include 5,000 usable units and 200–300 carefully reviewed cards, subject to owner approval.
7. Export a small SQLite test database and run integrity/foreign-key, search, duplicate, provenance and repeat-import tests. Coordinate exact Room schema with Android project before final export. Measure packaged size and Android search latency later.
8. Review results with project owner before large-scale import. Keep Stage 2/3 out of scope.

## 10. Status and limitations
The preliminary notebook audit is complete. Final source selection, legal clearance, independent rerun, reverse search, lemma/form classification, sense alignment, representative linguistic quality assessment, production ETL, SQLite/Room integration, performance and Stage 1 acceptance remain pending. This report is not a claim that the product or dictionary is ready for distribution. Recommended architecture and source choices are proposals rather than silently approved decisions.
