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
            var reverse = repository.search("ДЕМО-ОБЪЕКТ",true);
            assertEquals(1,reverse.size());
            var article = repository.entry(reverse.get(0).entryId());
            assertEquals("noun",article.pos());
            assertEquals(2,article.senses().size());
            assertEquals(1,article.senses().get(0).examples().size());
            assertTrue(article.senses().get(1).examples().isEmpty());
            assertTrue(article.attribution().contains("synthetic"));
            try (InputStream invalid = context.getAssets().open("dictionary/manifest.json")) {
                assertThrows(SQLiteException.class,() -> repository.install(invalid,new ByteArrayInputStream(new byte[]{1})));
            }
            assertThrows(UnsupportedOperationException.class,() -> repository.install(
                    new ByteArrayInputStream("{\"package_format\":2,\"schema_version\":2,\"language_pairs\":[]}"
                            .getBytes(StandardCharsets.UTF_8)),new ByteArrayInputStream(new byte[0])));
            repository.close();
            repository.load();
            assertEquals(1,repository.search("демо-объект",true).size());
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
                library.edit(savedId,"custom","note");
                var collection = new PersonalDatabase.Collection();
                collection.name = "fixtures";
                long collectionId = library.create(collection);
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
                library.remove(savedId);
                assertEquals(0,library.cardCount());
                assertEquals(1,library.reviewCount());
            } finally { reopened.close(); }
        } finally {
            context.deleteDatabase(filename);
        }
    }
}
