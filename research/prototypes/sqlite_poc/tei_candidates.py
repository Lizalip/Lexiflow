"""Inspect FreeDict TEI without aligning senses across sources."""
import lzma
import re
import tarfile
import xml.etree.ElementTree as ET
from xml.parsers import expat

XML = '{http://www.w3.org/XML/1998/namespace}'
TEI = '{http://www.tei-c.org/ns/1.0}'
MAX_PREFIX = 65_536
MAX_ARCHIVE = 30 * 1024**2
MAX_EXPANDED = 64 * 1024**2
ENTRY = re.compile(rb'<entry(?=[\s>])[^>]*>.*?</entry\s*>',re.S)


def children(element,name):
    return [node for node in element if node.tag.rsplit('}',1)[-1] == name]


def body(element):
    return ''.join(element.itertext()).strip()


def fields(element, path, name, language=None):
    return [
        dict(locator=f'{path}/{name}/{i}', text=body(node),
             language=node.get(XML+'lang', language), type=node.get('type'),
             attributes=dict(node.attrib))
        for i, node in enumerate(element.findall(TEI+name)) if body(node)
    ]


def parse_sense(element, path, language):
    language = element.get(XML+'lang', language)
    citations = []
    for i, citation in enumerate(element.findall(TEI+'cit')):
        citation_path = f'{path}/cit/{i}'
        quotes = fields(citation, citation_path, 'quote', citation.get(XML+'lang'))
        nested = []
        for j, child in enumerate(citation.findall(TEI+'cit')):
            if child.get('type') == 'trans':
                nested.extend(fields(child, f'{citation_path}/cit/{j}', 'quote', child.get(XML+'lang')))
        citations.append(dict(type=citation.get('type'), quotes=quotes,
                              nested_quotes=nested, references=fields(citation, citation_path, 'bibl')))
    return dict(locator=path, source_id=element.get(XML+'id'),
                definitions=fields(element, path, 'def', language),
                labels=fields(element, path, 'usg', language), citations=citations,
                senses=[parse_sense(sense, f'{path}/sense/{i}', language)
                        for i, sense in enumerate(element.findall(TEI+'sense'))])


def parse_entry(element, path, language):
    language = element.get(XML+'lang', language)
    forms = []
    for i, form in enumerate(element.findall(TEI+'form')):
        loc = f'{path}/form/{i}'
        lang = form.get(XML+'lang', language)
        forms.append(dict(type=form.get('type'), orthographies=fields(form, loc, 'orth', lang),
                          pronunciations=fields(form, loc, 'pron', lang),
                          grammar=[dict(locator=f'{loc}/gramGrp/{j}', value=body(group),
                                        features=[(x.tag.removeprefix(TEI), body(x)) for x in group])
                                   for j, group in enumerate(form.findall(TEI+'gramGrp'))]))
    return dict(locator=path, source_id=element.get(XML+'id'), language=language, forms=forms,
                pos=[value for i, group in enumerate(element.findall(TEI+'gramGrp'))
                     for value in fields(group, f'{path}/gramGrp/{i}', 'pos')],
                senses=[parse_sense(sense, f'{path}/sense/{i}', language)
                        for i, sense in enumerate(element.findall(TEI+'sense'))],
                rights='pending')


def stream_tei(stream):
    """Yield the header and direct TEI body entries, keeping only a chunk of XML in memory."""
    parser = expat.ParserCreate(namespace_separator='}')
    stack = []
    pending = []
    builder = None
    start_depth = 0
    count = 0
    body_path = (TEI+'TEI', TEI+'text', TEI+'body')

    def qname(name):
        return '{'+name if '}' in name else name

    def start(name, attributes):
        nonlocal builder, start_depth
        tag = qname(name)
        attrs = {qname(key): value for key, value in attributes.items()}
        language = attrs.get(XML+'lang', stack[-1][1] if stack else None)
        parents = tuple(parent for parent, _ in stack)
        if not stack and tag != TEI+'TEI':
            raise ValueError('expected TEI P5 document')
        stack.append((tag, language))
        if len(stack) > 64:
            raise ValueError('TEI nesting exceeds 64 nodes')
        if (parents == (TEI+'TEI',) and tag == TEI+'teiHeader'
                or parents == body_path and tag == TEI+'entry'):
            builder = ET.TreeBuilder()
            start_depth = len(stack)
        if builder is not None:
            builder.start(tag, attrs)

    def end(name):
        nonlocal builder, count
        tag, language = stack[-1]
        if builder is not None:
            builder.end(tag)
            if len(stack) == start_depth:
                tree = builder.close()
                builder = None
                if tag == TEI+'teiHeader':
                    file_desc = tree.find(TEI+'fileDesc')
                    header = ({} if file_desc is None else {
                        key: file_desc.findtext('/'.join(TEI+part for part in path.split('/')))
                        for key, path in {'edition':'editionStmt/edition', 'extent':'extent'}.items()
                    })
                    header['links'] = [node.get('target') for node in tree.iter(TEI+'ref')
                                       if node.get('target')]
                    pending.append(('header', header))
                else:
                    count += 1
                    pending.append(('entry', parse_entry(tree, f'entry/{count}', language)))
        stack.pop()

    def doctype(name, system_id, public_id, internal_subset):
        if (name, system_id, public_id, internal_subset) != ('TEI','freedict-P5.dtd',None,0):
            raise ValueError('unexpected TEI doctype')

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = lambda value: builder.data(value) if builder is not None else None
    parser.StartDoctypeDeclHandler = doctype
    parser.ExternalEntityRefHandler = lambda *args: 0
    remaining = MAX_EXPANDED
    while True:
        chunk = stream.read(min(65536, remaining+1))
        remaining -= len(chunk)
        if remaining < 0:
            raise ValueError('TEI exceeds 64 MiB')
        parser.Parse(chunk, not chunk)
        yield from pending
        pending.clear()
        if not chunk:
            break


def archive_tei(path, direction='eng-rus'):
    """Read the pinned release member in place, without extracting any archive paths."""
    if path.stat().st_size > MAX_ARCHIVE:
        raise ValueError('archive exceeds 30 MiB')
    if direction not in ('eng-rus','rus-eng'):
        raise ValueError('unknown FreeDict direction')

    class BoundedArchive:
        def __init__(self, stream):
            self.stream = stream
            self.remaining = MAX_EXPANDED

        def read(self, size):
            data = self.stream.read(min(size, self.remaining+1))
            self.remaining -= len(data)
            if self.remaining < 0:
                raise ValueError('expanded archive exceeds 64 MiB')
            return data

    with lzma.open(path, 'rb') as compressed, tarfile.open(
            fileobj=BoundedArchive(compressed), mode='r|') as archive:
        found = False
        for member in archive:
            if member.name != f'{direction}/{direction}.tei':
                continue
            if found or not member.isfile() or member.size > MAX_EXPANDED:
                raise ValueError('unexpected FreeDict TEI archive member')
            found = True
            with archive.extractfile(member) as stream:
                yield from stream_tei(stream)
        if not found:
            raise ValueError('FreeDict TEI archive member is absent')


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
