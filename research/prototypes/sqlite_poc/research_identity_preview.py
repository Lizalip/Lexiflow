"""Preview exact Research-to-production mappings without touching personal data."""


def preview(research, production):
    # The fingerprint binds the source text, POS and, for senses, the parent locator.
    # Production rows must be read from the already reviewed identity registry and export.
    def source_key(row):
        return row['kind'],row['snapshot'],row['locator']

    targets = {}
    used_ids = set()
    for row in production:
        key = source_key(row)
        if key in targets or row['id'] in used_ids or not row['fingerprint']:
            raise ValueError('production identity is ambiguous or lacks a fingerprint')
        targets[key] = row
        used_ids.add(row['id'])
    seen = set()
    existing = set()
    result = []
    for row in research:
        key = source_key(row)
        if key in seen or row['id'] in existing or not row['fingerprint']:
            raise ValueError('research identity is ambiguous or lacks a fingerprint')
        seen.add(key)
        existing.add(row['id'])
        target = targets.get(key)
        status = 'removed' if target is None else ('unchanged' if row['fingerprint']==target['fingerprint'] else 'changed')
        result.append(dict(research_id=row['id'], production_id=target['id'] if status=='unchanged' else None,
                           status=status,kind=key[0],snapshot=key[1],locator=key[2]))
    return result
