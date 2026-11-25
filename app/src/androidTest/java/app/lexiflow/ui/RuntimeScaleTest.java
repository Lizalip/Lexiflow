package app.lexiflow.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.data.DictionaryRepository;
import app.lexiflow.data.LocalDictionaryRepository;
import app.lexiflow.data.PersonalDatabase;
import app.lexiflow.data.ReviewScheduler;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RuntimeScaleTest {
    private static final String TAG = "LexiFlowScale";
    private static final int REPEATS = 5;

    @Test public void measuresOptInHundredThousandEntryCompactPackage() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var testAssets = instrumentation.getContext().getAssets();
        var target = instrumentation.getTargetContext();
        String assets = "runtime-capacity/100000-compact";
        Assume.assumeTrue("opt-in artificial capacity fixture",testAssets.list(assets).length == 2);
        Assume.assumeTrue("500 MiB free for isolated package copies",
                target.getCacheDir().getUsableSpace() >= 500L * 1024 * 1024);
        File root = new File(target.getCacheDir(),"capacity-" + UUID.randomUUID());
        var repository = new LocalDictionaryRepository(new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
        });
        try {
            measurePss("100000 before_install");
            long start = SystemClock.elapsedRealtimeNanos();
            try (var json = testAssets.open(assets+"/manifest.json");
                 var db = testAssets.open(assets+"/dictionary.sqlite")) {
                repository.install(json,db);
            }
            assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
            assertTrue(repository.isSynthetic());
            Log.i(TAG,"100000 install n=1 ms="+(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0);
            measurePss("100000 after_install");
            timed("100000 reopen",3,() -> {
                repository.close();
                repository.load();
                assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
            });
            timed("100000 exact",() -> assertEquals(1,repository.search("artificial-scale-000001",false).size()));
            timed("100000 prefix",() -> assertEquals(50,repository.search("artificial-scale-0001",false).size()));
            timed("100000 broad",() -> assertEquals(50,repository.search("artificial-",false).size()));
            timed("100000 reverse",() -> assertEquals(50,repository.search("artificial-answer-",true).size()));
            measurePss("100000 after_search");
            var hits = repository.search("artificial-scale-0001",false);
            for (var hit : hits.subList(0,5)) {
                assertEquals(hit.spelling(),repository.entry(hit.entryId()).spelling());
            }
            timed("100000 entry",() -> assertEquals(hits.get(0).spelling(),repository.entry(hits.get(0).entryId()).spelling()));
            measurePss("100000 after_articles");
        } finally {
            repository.close();
            clearScratchDirectory(root);
        }
    }

    @Test public void roomPagesTenThousandSavesAndOneHundredCollections() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "scale-" + UUID.randomUUID() + ".sqlite";
        PersonalDatabase database = Room.databaseBuilder(context,PersonalDatabase.class,name).build();
        try {
            var library = database.library();
            database.runInTransaction(() -> {
                long[] ids = new long[100];
                for (int i = 0; i < ids.length; i++) {
                    var collection = new PersonalDatabase.Collection();
                    collection.name = "Fixture " + i;
                    ids[i] = library.create(collection);
                }
                for (int i = 0; i < 10_000; i++) {
                    var saved = new PersonalDatabase.Saved();
                    saved.entryId = "fixture" + i;
                    saved.spelling = saved.entryId;
                    var member = new PersonalDatabase.Member();
                    member.collectionId = ids[i % ids.length];
                    member.savedId = library.save(saved);
                    library.addMember(member);
                }
            });
            assertEquals(10_000,library.savedCount());
            assertEquals(100,library.collections().size());
            long collection = library.collections().get(0).id;
            assertEquals(100,library.collectionSize(collection));
            var starts = library.savedPage(50,0);
            var next = library.savedPage(50,50);
            assertEquals(50,starts.size());
            assertEquals(50,next.size());
            assertTrue(starts.get(49).id > next.get(0).id);
            assertEquals("fixture0",library.savedPage(50,9950).get(49).spelling);
            assertEquals(50,library.collectionPage(collection,50,50).size());
            try (var indexes = database.getOpenHelper().getReadableDatabase().query(
                    "EXPLAIN QUERY PLAN SELECT savedId FROM members WHERE collectionId=1 ORDER BY savedId DESC LIMIT 50")) {
                assertTrue(indexes.moveToFirst());
                assertTrue(indexes.getString(3).contains("INDEX"));
            }
            try (var foreignKeys = database.getOpenHelper().getReadableDatabase().query("PRAGMA foreign_key_check")) {
                assertFalse(foreignKeys.moveToFirst());
            }
            timed("room_saved_last",() -> assertEquals(50,library.savedPage(50,9950).size()));
            timed("room_collection",() -> assertEquals(50,library.collectionPage(collection,50,50).size()));
            String pattern = FoundationViewModel.savedPattern("fixture99");
            timed("room_filtered_count",() -> assertEquals(111,library.filteredSavedCount(false,pattern)));
            timed("room_filtered_page",() -> assertEquals(50,library.filteredSavedPage(false,pattern,true,50,50).size()));
            long savedId = starts.get(0).id;
            assertEquals(1,library.edit(savedId,"custom answer","note"));
            for (boolean reverse : new boolean[]{false,true}) {
                var card = new PersonalDatabase.Card();
                card.savedId = savedId;
                card.due = 1000;
                card.reverse = reverse;
                assertTrue(library.addCard(card) > 0);
                assertEquals(-1,library.addCard(card));
            }
            for (int rating : new int[]{1,2}) {
                var exercise = library.next(1000);
                assertEquals(rating == 2,exercise.reverse);
                assertEquals("custom answer",exercise.answer);
                assertTrue(library.grade(exercise,rating,1000,ReviewScheduler::basic));
                assertFalse(library.grade(exercise,rating,1000,ReviewScheduler::basic));
            }
            assertEquals(2,library.reviewCount());
            assertEquals(0,library.dueCount(1000));
        } finally {
            database.close();
            context.deleteDatabase(name);
        }
    }

    @Test public void compareDictionaryPackagesInInstrumentationStorage() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context test = instrumentation.getContext();
        Context target = instrumentation.getTargetContext();
        Assume.assumeTrue("500 MiB free for isolated package copies",
                target.getCacheDir().getUsableSpace() >= 500L * 1024 * 1024);
        for (int size : new int[]{5000,20000}) {
            for (String format : new String[]{"flat","compact"}) {
                String assets = "runtime-scale/" + size + "-" + format;
                Assume.assumeTrue("optional local benchmark assets",test.getAssets().list(assets).length == 2);
            }
        }
        for (int size : new int[]{5000,20000}) {
            for (String format : new String[]{"flat","compact"}) {
                String label = size + "-" + format;
                String assets = "runtime-scale/" + label;
                File root = new File(target.getCacheDir(),"scale-" + UUID.randomUUID());
                var repository = new LocalDictionaryRepository(new ContextWrapper(target) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public File getFilesDir() { return root; }
                });
                try {
                    timed(label+" install",() -> {
                        try (var json = test.getAssets().open(assets+"/manifest.json");
                             var db = test.getAssets().open(assets+"/dictionary.sqlite")) {
                            repository.install(json,db);
                            assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    });
                    timed(label+" load_with_validation",() -> {
                        repository.close();
                        repository.load();
                        assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
                    });
                    var exact = repository.search("artificial-scale-000001",false);
                    assertEquals(1,exact.size());
                    String id = exact.get(0).entryId();
                    timed(label+" exact",() -> assertEquals(1,repository.search("artificial-scale-000001",false).size()));
                    timed(label+" prefix",() -> assertEquals(50,repository.search("artificial-scale-0001",false).size()));
                    timed(label+" broad",() -> assertEquals(50,repository.search("artificial-",false).size()));
                    timed(label+" reverse",() -> assertEquals(50,repository.search("artificial-answer-",true).size()));
                    timed(label+" entry",() -> assertEquals("artificial-scale-000001",repository.entry(id).spelling()));
                    measurePss(label);
                } finally {
                    repository.close();
                    clearScratchDirectory(root);
                }
            }
        }
    }

    private static void timed(String label,Runnable action) {
        timed(label,REPEATS,action);
    }

    private static void timed(String label,int repeats,Runnable action) {
        long[] times = new long[repeats];
        for (int i = 0; i < repeats; i++) {
            long start = SystemClock.elapsedRealtimeNanos();
            action.run();
            times[i] = SystemClock.elapsedRealtimeNanos()-start;
        }
        Arrays.sort(times);
        Log.i(TAG,label+" n="+repeats+" median_ms="+times[repeats/2]/1_000_000.0
                +" p95_ms="+times[(repeats*95-1)/100]/1_000_000.0
                +" min_ms="+times[0]/1_000_000.0+" max_ms="+times[repeats-1]/1_000_000.0);
    }

    private static void measurePss(String label) {
        Debug.MemoryInfo memory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memory);
        Log.i(TAG,label+" process_pss_kib="+memory.getTotalPss());
    }

    private static void clearScratchDirectory(File root) throws Exception {
        if (!root.exists()) return;
        try (var paths = Files.walk(root.toPath())) {
            for (var path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(path);
            }
        }
    }
}
