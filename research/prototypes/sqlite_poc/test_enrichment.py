"""Synthetic regressions for the corpus milestone."""
import io
import json
import sqlite3
import tarfile
import unittest
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile

from candidate_rank import SUBTLEX_MEMBER, subtlex_frequency
from corpus_enrichment import coverage, import_freedict, prepare_kaikki_metadata
from enrichment_benchmark import flat_manifest_sizes
from kaikki import import_sample
from prototype import create_database
from release_package import expand_attributions, validate_package
from review_queue import EDITOR_FIELDS, FIELDS, apply, triage_rows
from scale_fixture import make
from tei_candidates import stream_tei
from wikdict_candidates import candidates

BODY = """<?xml version='1.0' encoding='UTF-8'?>
<!DOCTYPE TEI SYSTEM 'freedict-P5.dtd'>
<TEI xmlns='http://www.tei-c.org/ns/1.0'><teiHeader><fileDesc>
<editionStmt><edition>2025.11.23</edition></editionStmt>
<publicationStmt><ref target='https://creativecommons.org/licenses/by-sa/3.0/legalcode'/></publicationStmt>
</fileDesc></teiHeader><text><body xml:lang='en'>
<entry xml:id='repeated'><form><orth>artificial</orth><pron notation='ipa'>/x/</pron></form>
<gramGrp><pos>n</pos></gramGrp><form type='inflected'><orth>artificials</orth>
<gramGrp><number>plural</number></gramGrp></form>
<sense xml:id='ambiguous'><cit type='trans' xml:lang='ru'><quote>объект</quote></cit>
<sense><def>artificial definition</def><usg type='reg'>informal</usg></sense>
<cit type='example'><quote>artificial sentence</quote>
<cit type='trans' xml:lang='ru'><quote>пример</quote></cit></cit></sense>
<sense><cit type='trans'><quote>unspecified</quote></cit></sense>
</entry>
<entry xml:id='repeated'><form><orth>artificial</orth></form><gramGrp><pos>v</pos></gramGrp>
<sense><def>second artificial definition</def></sense></entry>
</body></text></TEI>"""


class EnrichmentTests(unittest.TestCase):
    def test_stream_preserves_scope_and_refuses_internal_doctype(self):
        rows = list(stream_tei(io.BytesIO(BODY.encode())))
        entry = rows[1][1]
        parent = entry['senses'][0]
        self.assertEqual(parent['citations'][0]['quotes'][0]['language'],'ru')
        self.assertEqual(parent['senses'][0]['definitions'][0]['locator'],'entry/1/sense/0/sense/0/def/0')
        self.assertIsNone(entry['senses'][1]['citations'][0]['quotes'][0]['language'])
        malicious = BODY.replace("'freedict-P5.dtd'>","'freedict-P5.dtd' [<!ENTITY x 'unexpected'>]>")
        with self.assertRaisesRegex(ValueError,'doctype'):
            list(stream_tei(io.BytesIO(malicious.encode())))

    def test_freedict_keeps_candidates_pending_and_metadata_scoped(self):
        with TemporaryDirectory() as temp, closing(sqlite3.connect(':memory:')) as db:
            path = Path(temp)/'fixture.tar.xz'
            raw = BODY.encode()
            with tarfile.open(path,'w:xz') as archive:
                member = tarfile.TarInfo('eng-rus/eng-rus.tei')
                member.size = len(raw)
                archive.addfile(member,io.BytesIO(raw))
            create_database(db)
            import_freedict(db,path)
            report = coverage(db,structured=True)
            self.assertEqual((report['entries'],report['normalized_spellings'],report['senses']), (2,1,4))
            self.assertEqual(report['structured_translation_candidates'],1)
            self.assertEqual(report['translated_examples'],1)
            self.assertEqual(report['spellings_with_multiple_pos'],1)
            metadata = dict(db.execute('SELECT kind,count(*) FROM lexical_metadata GROUP BY kind'))
            self.assertEqual(metadata,{'definition':2,'grammar':1,'ipa':1,'style':1})
            db.row_factory = sqlite3.Row
            row, = triage_rows(db,'standard')
            self.assertEqual(row['definition_context'],['artificial definition'])
            self.assertEqual(row['rights'],'pending')
            with self.assertRaisesRegex(ValueError,'proposal cannot be applied'):
                apply(db,[{key:row[key] for key in FIELDS | EDITOR_FIELDS}])
            self.assertEqual(db.execute('SELECT count(*) FROM translation').fetchone()[0],0)

    def test_frequency_counts_rank_spellings_and_retain_other_parts_of_speech(self):
        with TemporaryDirectory() as temp, closing(sqlite3.connect(':memory:')) as db:
            archive = Path(temp)/'frequency.zip'
            with ZipFile(archive,'w') as fixture:
                fixture.writestr(SUBTLEX_MEMBER,'Word\tFREQlow\nArtificial\t19\nartificial\t20\nrare\t2\n')
            counts = subtlex_frequency(archive)
            self.assertEqual(counts,{'artificial':20,'rare':2})
            path = Path(temp)/'source.jsonl'
            records = [dict(word=word,pos=pos,lang_code='en',senses=[dict(glosses=glosses)])
                       for word,pos,glosses in [('rare','noun',['объект']),('Artificial','noun',['объект']),
                                                 ('artificial','verb',['сложный; контекст'])]]
            path.write_text('\n'.join(json.dumps(record) for record in records),encoding='utf-8')
            create_database(db)
            report = import_sample(db,path,method='frequency',limit=1,frequency=counts,frequency_sha256='a'*64)
            self.assertEqual(report['selection']['selected_frequency_observed'],1)
            self.assertEqual(db.execute('SELECT pos FROM entry ORDER BY pos').fetchall(),[('noun',),('verb',)])
            self.assertEqual(db.execute('SELECT count(*) FROM sense').fetchone()[0],2)

    def test_kaikki_metadata_uses_explicit_fields_without_inventing_cefr(self):
        with TemporaryDirectory() as temp, closing(sqlite3.connect(':memory:')) as db:
            path = Path(temp)/'source.jsonl'
            record = dict(word='artificial',pos='noun',lang_code='en',
                          senses=[dict(glosses=['объект'],tags=['colloquial','B2'])],
                          sounds=[dict(ipa='/x/',tags=['plural'])],
                          forms=[dict(form='artificials',tags=['plural'])])
            path.write_text(json.dumps(record),encoding='utf-8')
            create_database(db)
            import_sample(db,path)
            self.assertEqual(prepare_kaikki_metadata(db),
                             {'source_gloss':1,'ipa':1,'pronunciation':1,'grammar':1,'style':1})
            self.assertEqual(db.execute("""SELECT count(*) FROM lexical_metadata m
                JOIN evidence ev ON ev.id=m.evidence_id WHERE ev.rights='pending'""").fetchone()[0],5)

    def test_compact_package_round_trips_full_attribution_and_rejects_bad_links(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            flat = make(root/'flat',4)
            compact = make(root/'compact',4,compact=True)
            self.assertEqual(compact['counts'],flat['counts'])
            self.assertEqual(compact['database_sha256'],flat['database_sha256'])
            self.assertEqual(expand_attributions(compact['policies'],compact['attributions']),flat['attributions'])
            self.assertEqual(flat_manifest_sizes(compact)['formatted_bytes'],
                             (root/'flat'/'manifest.json').stat().st_size)
            self.assertEqual(len(compact['policies']),1)
            manifest = root/'compact'/'manifest.json'
            next(iter(compact['attributions'].values()))[0] = True
            manifest.write_text(json.dumps(compact),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'invalid compact attribution link'):
                validate_package(root/'compact')

    def test_duplicate_manifest_reference_is_rejected_before_json_can_overwrite_it(self):
        with TemporaryDirectory() as temp:
            path = Path(temp)/'compact'
            data = make(path,2,compact=True)
            ref, link = next(iter(data['attributions'].items()))
            identifier = json.dumps(ref)
            value = json.dumps(link,separators=(',',':'))
            manifest = path/'manifest.json'
            manifest.write_text(manifest.read_text(encoding='utf-8').replace(
                f'{identifier}:{value}',f'{identifier}:{value},{identifier}:{value}',1),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'duplicate manifest identifier'):
                validate_package(path)

    def test_wikdict_leaves_missing_sense_links_unaligned_and_disables_writes(self):
        with closing(sqlite3.connect(':memory:')) as db:
            db.executescript("""
                CREATE TABLE translation(
                    lexentry,sense_num,sense,written_rep,trans_list,score,is_good,importance
                );
                INSERT INTO translation VALUES
                    ('fixture/n',1,'artificial sense','artificial','пример | объект',4,1,1),
                    (NULL,NULL,'','artificial','пример',1,0,1);
            """)
            rows = list(candidates(db,'en-ru'))
            self.assertEqual(len(rows),2)
            self.assertEqual(rows[0]['raw_translation_list'],'пример | объект')
            self.assertIsNone(rows[1]['lexentry'])
            self.assertEqual(rows[1]['rights'],'pending')
            with self.assertRaises(sqlite3.OperationalError):
                db.execute('DELETE FROM translation')
