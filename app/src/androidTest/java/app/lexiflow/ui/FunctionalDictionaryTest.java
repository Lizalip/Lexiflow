package app.lexiflow.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteException;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.data.DictionaryRepository;
import app.lexiflow.data.LocalDictionaryRepository;
import app.lexiflow.data.PersonalDatabase;
import app.lexiflow.data.ReviewScheduler;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FunctionalDictionaryTest {
    @Test public void releaseRepositoryDoesNotAcceptExternalDebugStreams() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File folder = new File(context.getCacheDir(),"release-" + java.util.UUID.randomUUID());
        var repository = new LocalDictionaryRepository(new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return folder; }
            @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
                var info = new android.content.pm.ApplicationInfo(context.getApplicationInfo());
                info.flags &= ~android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;
                return info;
            }
        });
        try (var json = context.getAssets().open("dictionary/manifest.json");
             var db = context.getAssets().open("dictionary/dictionary.sqlite")) {
            assertThrows(UnsupportedOperationException.class,() -> repository.install(json,db));
            repository.load();
            assertEquals(DictionaryRepository.Status.MISSING,repository.status());
            assertFalse(folder.exists());
        } finally {
            repository.close();
        }
    }

    @Test public void nestedContextKeepsTranslationSenseIdentity() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.junit.Assume.assumeTrue(context.getAssets().list("dictionary-context").length > 0);
        File folder = new File(context.getCacheDir(),"context-" + java.util.UUID.randomUUID());
        var repository = new LocalDictionaryRepository(new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return folder; }
        });
        var personal = Room.inMemoryDatabaseBuilder(context,PersonalDatabase.class).build();
        try (var flat = context.getAssets().open("dictionary-v2/manifest.json");
             var before = context.getAssets().open("dictionary-v2/dictionary.sqlite");
             var nested = context.getAssets().open("dictionary-context/manifest.json");
             var after = context.getAssets().open("dictionary-context/dictionary.sqlite")) {
            repository.install(flat,before);
            var hit = repository.search("демо-объект",true).get(0);
            assertNull(repository.entry(hit.entryId()).senses().get(0).context());
            var saved = new PersonalDatabase.Saved();
            saved.entryId = hit.entryId();
            saved.senseId = hit.senseId();
            assertTrue(personal.library().save(saved) > 0);
            repository.install(nested,after);
            assertEquals(hit,repository.search("демо-объект",true).get(0));
            var entry = repository.entry(hit.entryId());
            assertEquals(2,entry.senses().size());
            assertEquals(personal.library().saved().get(0).senseId,entry.senses().get(0).id());
            assertEquals("[Artificial nested context]",entry.senses().get(0).context().text());
            assertTrue(entry.senses().get(0).context().definitionLocator().endsWith("/sense/0/def/0"));
            assertNull(entry.senses().get(1).context());
        } finally {
            personal.close();
            repository.close();
            if (folder.exists()) {
                try (var files = java.nio.file.Files.walk(folder.toPath())) {
                    for (var path : files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                        java.nio.file.Files.delete(path);
                    }
                }
            }
        }
    }

    @Test public void exportedFixtureSupportsSearchEntryAndFailedReplacement() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File folder = new File(context.getCacheDir(), "dictionary-test-" + java.util.UUID.randomUUID());
        var repository = new LocalDictionaryRepository(new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return folder; }
        });
        try (InputStream manifest = context.getAssets().open("dictionary/manifest.json");
             InputStream database = context.getAssets().open("dictionary/dictionary.sqlite")) {
            repository.install(manifest,database);
            assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
            assertTrue(repository.isSynthetic());
            assertTrue(repository.search(" \u00a0\t",false).isEmpty());
            char[] overlong = new char[257];
            java.util.Arrays.fill(overlong,'a');
            assertTrue(repository.search(new String(overlong),false).isEmpty());
            assertEquals(3,repository.search("ZETA-DEMO",false).size());
            assertEquals(4,repository.search("zeta",false).size());
            assertEquals(2,repository.search("zeta-demos",false).size());
            assertEquals("zeta-demos",repository.search("zeta-demos",false).get(0).matchedForm());
            var reverse = repository.search("ДЕМО-ОБЪЕКТ",true);
            assertEquals(1,reverse.size());
            assertEquals("демо-объект",reverse.get(0).translation());
            var article = repository.entry(reverse.get(0).entryId());
            assertEquals("noun",article.pos());
            assertEquals(2,article.senses().size());
            assertEquals(1,article.senses().get(0).examples().size());
            assertTrue(article.senses().get(1).examples().isEmpty());
            assertTrue(article.attribution().contains("synthetic"));
            assertTrue(article.metadata().isEmpty());
            try (InputStream invalid = context.getAssets().open("dictionary/manifest.json")) {
                assertThrows(SQLiteException.class,() -> repository.install(invalid,new ByteArrayInputStream(new byte[]{1})));
            }
            try (InputStream metadata = context.getAssets().open("dictionary/manifest.json")) {
                assertThrows(IOException.class,() -> repository.install(metadata,new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("artificial interruption"); }
                }));
            }
            assertThrows(UnsupportedOperationException.class,() -> repository.install(
                    new ByteArrayInputStream("{\"package_format\":2,\"schema_version\":3,\"language_pairs\":[]}"
                            .getBytes(StandardCharsets.UTF_8)),new ByteArrayInputStream(new byte[0])));
            repository.close();
            repository.load();
            assertEquals(1,repository.search("демо-объект",true).size());
            repository.installBundledV2();
            var enriched = repository.entry(article.id());
            assertEquals(4,enriched.metadata().size());
            assertEquals(4,enriched.senses().get(0).metadata().size());
            assertTrue(enriched.senses().get(1).metadata().isEmpty());
            try (InputStream metadata = context.getAssets().open("dictionary-v2/manifest.json")) {
                var compact = compactFixture(new JSONObject(new java.util.Scanner(metadata, "UTF-8")
                        .useDelimiter("\\A").next()));
                installFixture(repository,context,compact.toString());
                assertEquals(enriched,repository.entry(article.id()));
                var pointer = new File(folder,"dictionary/active").toPath();
                byte[] activePackage = java.nio.file.Files.readAllBytes(pointer);
                var links = compact.getJSONObject("attributions");
                String ref = links.keys().next();
                var link = links.getJSONArray(ref);
                String valid = compact.toString();
                String duplicate = valid.replace("\""+ref+"\":","\""+ref+"\":"+link+",\""+ref+"\":");
                assertThrows(JSONException.class,() -> installFixture(repository,context,duplicate));
                link.put(0,compact.getJSONArray("policies").length());
                assertThrows(JSONException.class,() -> installFixture(repository,context,compact.toString()));
                link.put(0,true);
                assertThrows(JSONException.class,() -> installFixture(repository,context,compact.toString()));
                link.put(0,0);
                var policies = compact.getJSONArray("policies");
                compact.remove("policies");
                assertThrows(JSONException.class,() -> installFixture(repository,context,compact.toString()));
                compact.put("policies",policies);
                policies.getJSONObject(0).put("license_id","changed license");
                assertThrows(JSONException.class,() -> installFixture(repository,context,compact.toString()));
                try (InputStream corrupt = new ByteArrayInputStream(new byte[]{1})) {
                    assertThrows(SQLiteException.class,() -> repository.install(jsonStream(valid),corrupt));
                }
                assertEquals(enriched,repository.entry(article.id()));
                assertArrayEquals(activePackage,java.nio.file.Files.readAllBytes(pointer));
                repository.close();
                repository.load();
                assertEquals(enriched,repository.entry(article.id()));
            }
        } finally {
            repository.close();
            if (folder.exists()) {
                try (var files = java.nio.file.Files.walk(folder.toPath())) {
                    for (var path : files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                        java.nio.file.Files.delete(path);
                    }
                }
            }
        }
    }

    private static ByteArrayInputStream jsonStream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void installFixture(LocalDictionaryRepository repository,Context context,String json) throws Exception {
        try (InputStream db = context.getAssets().open("dictionary-v2/dictionary.sqlite")) {
            repository.install(jsonStream(json),db);
        }
    }

    private static JSONObject compactFixture(JSONObject flat) throws JSONException {
        var links = new JSONObject();
        JSONObject policy = null;
        JSONObject original = flat.getJSONObject("attributions");
        for (var refs = original.keys(); refs.hasNext();) {
            String id = refs.next();
            JSONObject entry = original.getJSONObject(id);
            String locator = (String) entry.remove("locator");
            String modification = (String) entry.remove("modification");
            if (policy == null) policy = entry;
            else assertEquals(policy.toString(),entry.toString());
            links.put(id,new JSONArray().put(0).put(locator).put(modification));
        }
        // All synthetic rows have the same policy. Keep a shared reference to it.
        return flat.put("package_format",2).put("attributions",links)
                .put("policies",new JSONArray().put(policy));
    }

    @Test public void personalLibraryPersistsAndPreservesReviewHistory() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String filename = "artificial-" + java.util.UUID.randomUUID() + ".sqlite";
        try {
            long savedId;
            PersonalDatabase db = Room.databaseBuilder(context,PersonalDatabase.class,filename).build();
            try {
                var library = db.library();
                var item = new PersonalDatabase.Saved();
                item.entryId = "missing-in-corpus";
                item.senseId = "artificial-sense";
                item.spelling = "artificial";
                item.answer = "fixture";
                savedId = library.save(item);
                assertTrue(savedId > 0);
                assertEquals(-1,library.save(item));
                assertEquals(1,library.edit(savedId,"custom","note"));
                var collection = new PersonalDatabase.Collection();
                collection.name = "fixtures";
                long collectionId = library.create(collection);
                assertEquals(-1,library.create(collection));
                assertEquals(1,library.rename(collectionId,"renamed"));
                var member = new PersonalDatabase.Member();
                member.collectionId = collectionId;
                member.savedId = savedId;
                library.addMember(member);
                assertEquals(1,library.memberships().size());
                library.removeMember(collectionId,savedId);
                assertTrue(library.memberships().isEmpty());
                assertEquals(1,library.saved().size());
                var card = new PersonalDatabase.Card();
                card.savedId = savedId;
                card.reverse = true;
                card.due = 1000;
                library.addCard(card);
                assertEquals(-1,library.addCard(card));
                assertEquals(1,library.cardCount());
                assertEquals(1,library.dueCount(1000));
                var exercise = library.next(1000);
                assertEquals("custom",exercise.answer);
                assertTrue(library.grade(exercise,2,1000,ReviewScheduler::basic));
                assertFalse(library.grade(exercise,2,1000,ReviewScheduler::basic));
                assertEquals(0,library.dueCount(1000));
            } finally { db.close(); }
            PersonalDatabase reopened = Room.databaseBuilder(context,PersonalDatabase.class,filename).build();
            try {
                var library = reopened.library();
                assertEquals("note",library.saved().get(0).note);
                assertEquals("renamed",library.collections().get(0).name);
                assertEquals(1,library.reviewCount());
                assertEquals(1,library.successCount());
                var entireEntry = new PersonalDatabase.Saved();
                entireEntry.entryId = "missing-in-corpus";
                entireEntry.spelling = "artificial";
                entireEntry.answer = "fixture";
                assertTrue(library.save(entireEntry) > 0);
                library.remove(savedId);
                assertEquals(0,library.edit(savedId,"unavailable",""));
                assertEquals(1,library.saved().size());
                assertEquals(0,library.cardCount());
                assertEquals(1,library.reviewCount());
            } finally { reopened.close(); }
        } finally {
            context.deleteDatabase(filename);
        }
    }
}
