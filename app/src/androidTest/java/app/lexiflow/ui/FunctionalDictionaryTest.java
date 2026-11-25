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
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FunctionalDictionaryTest {
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
