# LexiFlow — experimental dictionary data foundation

Standalone Python/SQLite staging prototype. Baseline: `feature/android-foundation`, `f53b6f1b`. No Android code or existing research materials are modified. This is not the approved dictionary schema, a distribution-ready corpus or production ETL.

## Run

From the repository root, with Python 3.10+ (verified on 3.13.5):

```powershell
python -m unittest discover -s research/prototypes/sqlite_poc -v
```

A local source file is required for an actual import. Only run this when the source exists and the destination is a new path in an ignored directory; do not synchronize or change the WSL environment just for this experiment.

```bash
mkdir -p research/data/processed
python research/prototypes/sqlite_poc/run_import.py \
  'research/data/raw/kaikki.org-dictionary-Английский.jsonl' \
  research/data/processed/kaikki_poc_100.sqlite --limit 100
```

The CLI streams the full file once for SHA-256, then imports at most 1,000 physical lines. It prints aggregate JSON to stdout. It refuses an existing output path and never changes the input. Import only into a fresh experimental database. Do not commit generated databases, real source text, raw source files or reports that contain unresolved quotations. On Windows use `python` or the available interpreter and equivalent PowerShell paths.

## Modules

Sampling modes are `--method first`, `--method random --seed 42` and `--method exact`. The exact diagnostic reads 24 spellings from the saved notebook. The source digest and original line locators remain stable across methods. Run `python research/prototypes/sqlite_poc/benchmark.py` for the artificial desktop benchmark. [Commands and results](../../../docs/DATABASE_POC_REPORT.md)

- `schema.sql`: core staging schema, provenance, language, entry/sense, gloss, translation, examples, pronunciation, form, issue tracking.
- `prototype.py`: schema creation, Unicode normalization, snapshot-local UUIDs, indexed bilingual lookup, integrity checking.
- `kaikki.py`: bounded JSONL adapter, nested validation, pending-rights staging, aggregate coverage counters.
- `run_import.py`: non-overwriting local export CLI.
- `normalization_vectors.json`: cross-language conformance examples for future Java implementation; Python tested, Java not tested.
- `test_prototype.py`: synthetic fixtures and regression tests.

## Semantics and safety

`research_lookup` (also exposed as `search`) returns internal English candidates and can include pending evidence. `release_lookup` requires cleared entry and sense evidence, plus cleared translation evidence for reverse text. These APIs expose only fields on those objects. A future article export must check rights for its glosses, examples, forms and media separately.

## Local review

Create `research/data/review/` locally and export tasks using `review_queue.py export DATABASE research/data/review/tasks.jsonl`. In a separate JSONL keep `entry_id`, `sense_id`, `snapshot_sha256` and `locator` from each reviewed task. Add a classification and reason. The classes are `source_gloss`, `candidate_translation`, `definition`, `form-reference` and `unclear`. Run `review_queue.py validate DATABASE research/data/review/decisions.jsonl` to check exact lineage and reject duplicate or conflicting decisions. The validator leaves the database and rights unchanged.

## Source identity

Imported evidence stays pending. Glosses and example translations remain separate, with language tags inferred from extraction context. Raw tags, forms and sound metadata remain available for review.

Snapshot identity is based on source, full SHA-256 and original JSONL locator, preserving duplicate source IDs and original array positions. It is reproducible within a snapshot; a persistent cross-release identity registry remains necessary. Logical dump reproducibility was tested, not byte-identical database files across SQLite versions.

See [experiment report](../../../docs/DATABASE_POC_CHAT_REPORT.md) for scope, measured results and remaining work.
