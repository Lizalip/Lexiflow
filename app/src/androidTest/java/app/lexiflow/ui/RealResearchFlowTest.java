package app.lexiflow.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.util.Log;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.R;
import app.lexiflow.data.DictionaryRepository;
import app.lexiflow.data.LocalDictionaryRepository;
import java.io.File;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static app.lexiflow.ui.IsolatedPersonalFlowTest.await;
import static app.lexiflow.ui.IsolatedPersonalFlowTest.capture;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class RealResearchFlowTest {
    private static final String TAG = "LexiFlowResearch";
    private static final String NOTE = "Заметка проверки исследовательской сборки";
    private static final String CUSTOM = "Личный вариант проверки";
    private static final String COLLECTION = "Подборка проверки";

    @Test public void usesRealDictionaryAndPersistsPersonalData() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(LocalDictionaryRepository.isResearchProfile(target));
        assertFalse(target.getDatabasePath("lexiflow-personal.sqlite").exists());
        long launch = SystemClock.elapsedRealtime();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitAvailable(scenario);
            onView(withId(R.id.research_banner)).check(matches(isDisplayed()));
            Log.i(TAG,"initial dictionary UI ms="+(SystemClock.elapsedRealtime()-launch));
            capture(scenario,"01-dictionary");
            onView(withId(R.id.direction)).perform(click());
            query("книга");
            awaitAvailable(scenario);
            assertTrue(view(scenario).hits().stream().anyMatch(row -> row.spelling().equals("book")));
            onView(withId(R.id.direction)).perform(click());
            long typed = SystemClock.elapsedRealtime();
            query("book");
            awaitAvailable(scenario);
            var hit = view(scenario).hits().stream()
                    .filter(row -> row.spelling().equals("book") && "n".equals(row.pos()))
                    .findFirst().orElseThrow(AssertionError::new);
            String label=hit.spelling()+" · "+hit.pos()+" · "+target.getString(R.string.sense_title,hit.position())
                    +"\n"+hit.translation();
            onView(withText(label)).check(matches(isDisplayed()));
            Log.i(TAG,"book input with debounce and rendered match ms="+(SystemClock.elapsedRealtime()-typed));
            capture(scenario,"02-results");
            onView(withText(label)).perform(scrollTo(),click());
            awaitAvailable(scenario);
            var article = view(scenario).entry();
            assertNotNull(article);
            assertTrue(article.senses().size()>1);
            assertTrue(article.metadata().stream().anyMatch(row -> row.kind().equals("ipa")));
            assertTrue(article.metadata().stream().anyMatch(row -> row.kind().equals("definition")));
            capture(scenario,"03-entry");
            onView(withText(target.getString(R.string.save_sense,hit.position()))).perform(scrollTo(),click());
            back();
            onView(withId(R.id.vocabulary)).perform(click());
            await(scenario,state -> state.savedCount()==1,"saved real sense");
            String[] actions = target.getResources().getStringArray(R.array.saved_actions);
            onView(withText(startsWith("book · "))).perform(scrollTo(),click());
            onView(withText(actions[1])).perform(click());
            onView(withHint(R.string.custom_translation)).perform(replaceText(CUSTOM));
            onView(withHint(R.string.note)).perform(replaceText(NOTE),closeSoftKeyboard());
            onView(withText(R.string.save)).perform(click());
            await(scenario,state -> state.saved().stream().anyMatch(row -> NOTE.equals(row.note)),"real sense edited");
            capture(scenario,"04-vocabulary");
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen()==Screen.COLLECTIONS,"collections opened");
            action(R.string.create_collection);
            onView(withHint(R.string.collection_name)).perform(replaceText(COLLECTION),closeSoftKeyboard());
            onView(withText(R.string.save)).perform(click());
            await(scenario,state -> state.collections().stream().anyMatch(row -> COLLECTION.equals(row.name)),"collection created");
            back();
            await(scenario,state -> state.screen()==Screen.VOCABULARY,"library restored");
            savedAction(actions[2]);
            onView(withText(COLLECTION)).perform(click());
            savedAction(actions[3]);
            savedAction(actions[4]);
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen()==Screen.COLLECTIONS,"collection page ready");
            onView(withText(COLLECTION)).perform(scrollTo(),click());
            await(scenario,state -> state.collection()!=0 && state.saved().size()==1,"real sense in collection");
            capture(scenario,"05-collection");
            back();
            back();
            onView(withId(R.id.review)).perform(click());
            await(scenario,state -> state.exercise()!=null && !state.exercise().reverse,"forward card");
            capture(scenario,"06-review-front");
            action(R.string.reveal);
            onView(withId(R.id.body)).check(matches(withText(CUSTOM)));
            capture(scenario,"07-review-answer");
            String[] grades = target.getResources().getStringArray(R.array.review_ratings);
            onView(withText(grades[1])).perform(scrollTo(),click());
            await(scenario,state -> state.exercise()!=null && state.exercise().reverse,"reverse card");
            action(R.string.reveal);
            onView(withId(R.id.body)).check(matches(withText("book")));
            onView(withText(grades[2])).perform(scrollTo(),click());
            await(scenario,state -> state.screen()==Screen.REVIEW && state.exercise()==null,"cards graded");
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen()==Screen.PROGRESS && state.reviewed()==2,"statistics updated");
            capture(scenario,"08-progress");
            onView(withId(R.id.settings)).perform(click());
            awaitAvailable(scenario);
            capture(scenario,"09-settings");
        }
        measureRepository(target);
        rejectInOrdinaryProfile(target);
    }

    @Test public void readsPersonalDataAfterNewProcess() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(LocalDictionaryRepository.isResearchProfile(target));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitAvailable(scenario);
            onView(withId(R.id.vocabulary)).perform(click());
            await(scenario,state -> state.saved().stream().anyMatch(row ->
                    row.spelling.equals("book") && row.note.equals(NOTE) && row.custom.equals(CUSTOM)),"personal data persisted");
            onView(withId(R.id.review)).perform(click());
            await(scenario,state -> state.screen()==Screen.REVIEW,"review loaded");
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen()==Screen.PROGRESS && state.reviewed()==2
                    && state.successful()==2 && state.cards()==2,"review history persisted");
        }
    }

    private static void measureRepository(Context target) {
        var repository = new LocalDictionaryRepository(target);
        try {
            measure("reopen",3,() -> {
                repository.close();
                repository.load();
                assertEquals(DictionaryRepository.Status.AVAILABLE,repository.status());
            });
            measure("exact",5,() -> assertTrue(repository.search("book",false).stream().anyMatch(row -> row.spelling().equals("book"))));
            measure("prefix",5,() -> assertFalse(repository.search("bo",false).isEmpty()));
            measure("reverse",5,() -> assertFalse(repository.search("книга",true).isEmpty()));
            var id = repository.search("book",false).get(0).entryId();
            measure("entry",5,() -> assertNotNull(repository.entry(id)));
        } finally {
            repository.close();
        }
    }

    private static void measure(String name,int n,Runnable operation) {
        double[] values = new double[n];
        for (int i=0;i<n;i++) {
            long started = SystemClock.elapsedRealtimeNanos();
            operation.run();
            values[i] = (SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0;
        }
        Arrays.sort(values);
        Log.i(TAG,name+" ms="+Arrays.toString(values));
    }

    private static void rejectInOrdinaryProfile(Context target) throws Exception {
        var ordinary = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public String getPackageName() { return "app.lexiflow.runtimecheck"; }
            @Override public File getFilesDir() { return new File(target.getCacheDir(),"profile-gate-"+UUID.randomUUID()); }
        };
        try (var json = target.getAssets().open("dictionary-research/manifest.json");
             var db = target.getAssets().open("dictionary-research/dictionary.sqlite")) {
            var repository = new LocalDictionaryRepository(ordinary);
            try {
                assertThrows(UnsupportedOperationException.class,() -> repository.install(json,db));
            } finally {
                repository.close();
            }
        }
    }

    private static FoundationViewModel.DictionaryView view(ActivityScenario<MainActivity> scenario) {
        var result = new java.util.concurrent.atomic.AtomicReference<FoundationViewModel.DictionaryView>();
        scenario.onActivity(activity -> result.set(new ViewModelProvider(activity)
                .get(FoundationViewModel.class).dictionaryView.getValue()));
        return result.get();
    }

    private static void awaitAvailable(ActivityScenario<MainActivity> scenario) throws InterruptedException {
        var settled = new CountDownLatch(1);
        androidx.lifecycle.Observer<FoundationViewModel.DictionaryView> observer = value -> {
            if (value!=null && value.status()!=DictionaryRepository.Status.LOADING) settled.countDown();
        };
        scenario.onActivity(activity -> new ViewModelProvider(activity).get(FoundationViewModel.class)
                .dictionaryView.observeForever(observer));
        try {
            assertTrue("research dictionary loaded",settled.await(40,TimeUnit.SECONDS));
            assertEquals(DictionaryRepository.Status.AVAILABLE,view(scenario).status());
            assertFalse(view(scenario).synthetic());
        } finally {
            scenario.onActivity(activity -> new ViewModelProvider(activity).get(FoundationViewModel.class)
                    .dictionaryView.removeObserver(observer));
        }
    }

    private static void query(String value) { onView(withId(R.id.query)).perform(click(),replaceText(value),closeSoftKeyboard()); }
    private static void back() { onView(withContentDescription(R.string.back)).perform(click()); }
    private static void action(int label) { onView(withText(label)).perform(scrollTo(),click()); }
    private static void savedAction(String action) {
        onView(withText(startsWith("book · "+CUSTOM))).perform(scrollTo(),click());
        onView(withText(action)).perform(click());
    }
}
