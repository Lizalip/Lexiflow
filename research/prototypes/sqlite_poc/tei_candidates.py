"""Inspect a bounded prefix of an EN→RU TEI P5 file; no cross-source sense alignment."""
import re
import xml.etree.ElementTree as ET

XML = '{http://www.w3.org/XML/1998/namespace}'
MAX_PREFIX = 65_536
ENTRY = re.compile(rb'<entry(?=[\s>])[^>]*>.*?</entry\s*>',re.S)


def children(element,name):
    return [node for node in element if node.tag.rsplit('}',1)[-1] == name]


def body(element):
    return ''.join(element.itertext()).strip()


def prefix_candidates(data):
    """Yield complete entries from a byte-zero prefix. Keep exact local locators."""
    if len(data) > MAX_PREFIX:
        raise ValueError('TEI prefix exceeds 64 KiB')
    for match in ENTRY.finditer(data):
        fragment = match.group()
        if b'<!DOCTYPE' in fragment or b'<!ENTITY' in fragment:
            raise ValueError('unexpected declarations in TEI entry')
        entry = ET.fromstring(fragment)
        entry_path = f'bytes/{match.start()}'
        orthography = [body(orth) for form in children(entry,'form') for orth in children(form,'orth') if body(orth)]
        senses = []
        unaligned = []
        for index,sense in enumerate(children(entry,'sense')):
            position = f'{entry_path}/sense/{index}'
            translations = []
            for ci,citation in enumerate(children(sense,'cit')):
                if citation.get('type') != 'trans':
                    continue
                language = citation.get(XML+'lang')
                for qi,quote in enumerate(children(citation,'quote')):
                    actual = quote.get(XML+'lang',language)
                    if not body(quote) or (actual is not None and actual not in ('ru','rus')):
                        continue
                    candidate = dict(locator=f'{position}/cit/{ci}/quote/{qi}', text=body(quote),
                                     target_language=actual, rights='pending')
                    (translations if actual else unaligned).append(candidate)
            senses.append(dict(locator=position,translations=translations,
                               definitions=[body(node) for node in children(sense,'def') if body(node)]))
        yield dict(locator=entry_path,source_id=entry.get(XML+'id'), headwords=orthography,
                   senses=senses,unspecified_target=unaligned,rights='pending')
