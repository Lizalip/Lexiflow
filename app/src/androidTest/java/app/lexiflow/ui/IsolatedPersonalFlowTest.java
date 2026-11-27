package app.lexiflow.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.R;
import app.lexiflow.data.LocalDictionaryRepository;
import app.lexiflow.data.PersonalDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class IsolatedPersonalFlowTest {
    @Test public void editsCollectionsReviewsAndKeepsDataAfterPackageUpdate() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("isolated profile required","app.lexiflow.runtimecheck".equals(context.getPackageName()));
        assertFalse("start with a fresh isolated profile",context.getDatabasePath("lexiflow-personal.sqlite").exists());
        var pointer = new java.io.File(context.getFilesDir(),"dictionary/active").toPath();
        var dictionary = new LocalDictionaryRepository(context);
        try (var json = context.getAssets().open("dictionary/manifest.json");
             var db = context.getAssets().open("dictionary/dictionary.sqlite")) {
            dictionary.install(json,db);
        } finally {
            dictionary.close();
        }
        byte[] originalPackage = Files.readAllBytes(pointer);
        String[] actions = context.getResources().getStringArray(R.array.saved_actions);
        String[] ratings = context.getResources().getStringArray(R.array.review_ratings);
        String collection = "Runtime fixture collection";
        String renamed = "Renamed runtime fixture";
        String custom = "Runtime test translation";
        String note = "Runtime test note";
        String savedLabel = "zeta-demo · " + custom + "\n" + note;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            NavigationTest.awaitDictionary(scenario);
            capture(scenario,"01-dictionary");
            onView(withId(R.id.query)).perform(click(),replaceText("zeta-demo"),closeSoftKeyboard());
            NavigationTest.awaitDictionary(scenario);
            capture(scenario,"02-results");
            onView(org.hamcrest.Matchers.allOf(withId(R.id.result_word),withText("zeta-demo"))).perform(scrollTo(),click());
            NavigationTest.awaitDictionary(scenario);
            capture(scenario,"03-entry");
            onView(withText(R.string.add_word)).perform(scrollTo(),click());
            onView(withText(R.string.word_in_vocabulary)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
            back();
            onView(withId(R.id.vocabulary)).perform(click());
            await(scenario,state -> state.screen() == Screen.VOCABULARY && state.savedCount() == 1,"one saved sense");
            onView(withText(startsWith("zeta-demo · демо-объект"))).perform(scrollTo(),click());
            onView(withText(actions[1])).perform(click());
            onView(withHint(R.string.custom_translation)).perform(replaceText(custom));
            onView(withHint(R.string.note)).perform(replaceText(note),closeSoftKeyboard());
            onView(withText(R.string.save)).perform(click());
            await(scenario,state -> state.saved().stream().anyMatch(item -> custom.equals(item.custom)
                    && note.equals(item.note)),"personal fields saved");
            capture(scenario,"04-vocabulary");
            action(R.string.show_saved_notes);
            await(scenario,state -> state.notesOnly() && state.rowCount() == 1,"note filter applied");
            action(R.string.show_all_saved);
            await(scenario,state -> !state.notesOnly() && state.savedCount() == 1,"note filter cleared");
            onView(withId(R.id.saved_query)).perform(scrollTo(),replaceText("TEST NOTE"),closeSoftKeyboard());
            await(scenario,state -> state.savedQuery().equals("TEST NOTE") && state.rowCount() == 1,"matches note");
            onView(withId(R.id.saved_query)).perform(replaceText("*"),closeSoftKeyboard());
            await(scenario,state -> state.savedQuery().equals("*") && state.rowCount() == 0,"literal wildcard");
            onView(withId(R.id.saved_query)).perform(clearText(),closeSoftKeyboard());
            await(scenario,state -> state.savedQuery().isEmpty() && state.rowCount() == 1,"search cleared");
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen() == Screen.COLLECTIONS,"collections loaded");
            action(R.string.create_collection);
            editCollection(collection);
            await(scenario,state -> state.collections().stream().anyMatch(item -> item.name.equals(collection)),
                    "collection created");
            onView(withText(collection)).perform(scrollTo(),click());
            await(scenario,state -> state.screen() == Screen.COLLECTIONS && state.collection() != 0,"collection opened");
            action(R.string.rename_collection);
            editCollection(renamed);
            await(scenario,state -> state.collections().stream().anyMatch(item -> item.name.equals(renamed)),
                    "collection renamed");
            back();
            await(scenario,state -> state.screen() == Screen.COLLECTIONS && state.collection() == 0,"collection list");
            back();
            await(scenario,state -> state.screen() == Screen.VOCABULARY,"vocabulary loaded");
            savedAction(savedLabel,actions[2]);
            onView(withText(renamed)).perform(click());
            savedAction(savedLabel,actions[3]);
            savedAction(savedLabel,actions[4]);
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen() == Screen.COLLECTIONS && state.collection() == 0,
                    "collection list reloaded");
            onView(withText(renamed)).perform(scrollTo(),click());
            await(scenario,state -> state.collection() != 0 && state.saved().size() == 1,"membership stored");
            capture(scenario,"05-collection");
            back();
            back();
            onView(withId(R.id.review)).perform(click());
            await(scenario,state -> state.screen() == Screen.REVIEW && state.learning().unstudied == 2,"new cards counted");
            onView(withText(context.getString(R.string.study_new_count,2))).perform(scrollTo(),click());
            await(scenario,state -> state.exercise() != null && !state.exercise().reverse,"forward exercise");
            onView(withId(R.id.panel_title)).check(matches(withText("zeta-demo")));
            capture(scenario,"06-review-front");
            action(R.string.reveal);
            onView(withId(R.id.body)).check(matches(withText(custom)));
            scenario.recreate();
            await(scenario,state -> state.exercise() != null && !state.exercise().reverse,"revealed exercise restored");
            onView(withId(R.id.body)).check(matches(withText(custom)));
            capture(scenario,"07-review-answer");
            onView(withText(startsWith(ratings[1]+" · "))).perform(scrollTo(),click());
            await(scenario,state -> state.exercise() != null && state.exercise().reverse,"reverse exercise");
            onView(withId(R.id.panel_title)).check(matches(withText(custom)));
            action(R.string.reveal);
            onView(withId(R.id.body)).check(matches(withText("zeta-demo")));
            onView(withText(startsWith(ratings[2]+" · "))).perform(scrollTo(),click());
            await(scenario,state -> state.screen() == Screen.STUDY && state.exercise() == null,"review queue completed");
            onView(withId(R.id.panel_title)).check(matches(withText(R.string.review_session_finished)));
            onView(withId(R.id.body)).check(matches(withText(context.getString(R.string.review_session_summary,2,2,0))));
            action(R.string.study_home);
            onView(withId(R.id.progress)).perform(click());
            await(scenario,state -> state.screen() == Screen.PROGRESS && state.reviewed() == 2
                    && state.successful() == 2,"reviews counted");
            capture(scenario,"08-progress");
            onView(withId(R.id.settings)).perform(click());
            NavigationTest.awaitDictionary(scenario);
            capture(scenario,"09-settings");
            action(R.string.install_debug_v2);
            scenario.recreate();
            NavigationTest.awaitDictionary(scenario);
            assertFalse("V2 was activated",java.util.Arrays.equals(originalPackage,Files.readAllBytes(pointer)));
        }
        try (ActivityScenario<MainActivity> reopened = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.vocabulary)).perform(click());
            await(reopened,state -> state.savedCount() == 1 && state.saved().stream().anyMatch(
                    item -> custom.equals(item.custom) && note.equals(item.note)), "data survived reopen and V2");
            onView(withText(savedLabel)).check(matches(isDisplayed()));
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(reopened,state -> state.screen() == Screen.COLLECTIONS
                    && state.collections().stream().anyMatch(item -> item.name.equals(renamed)), "collection survived reopen");
            onView(withText(renamed)).perform(scrollTo(),click());
            await(reopened,state -> state.collection() != 0 && state.saved().size() == 1,"membership survived reopen");
            action(R.string.delete_collection);
            onView(withText(R.string.remove)).perform(click());
            await(reopened,state -> state.screen() == Screen.COLLECTIONS && state.collection() == 0
                    && state.collections().isEmpty(),"collection deleted");
            back();
            await(reopened,state -> state.screen() == Screen.VOCABULARY && state.savedCount() == 1,
                    "saved entry preserved after collection deletion");
            onView(withText(savedLabel)).check(matches(isDisplayed()));
            reopened.onActivity(activity -> new ViewModelProvider(activity).get(FoundationViewModel.class)
                    .changePersonal(library -> {
                        for (int i = 0; i < 32; i++) {
                            var saved = new PersonalDatabase.Saved();
                            saved.entryId = "missing-fixture-" + i;
                            saved.spelling = "Runtime scroll fixture " + i;
                            library.save(saved);
                        }
                    }));
            await(reopened,state -> state.screen() == Screen.VOCABULARY && state.savedCount() == 33,"long saved list");
            onView(withText(savedLabel)).perform(scrollTo());
            int[] scrollThroughSettings = new int[1];
            reopened.onActivity(activity ->
                    scrollThroughSettings[0] = activity.findViewById(R.id.scroll).getScrollY());
            assertTrue(scrollThroughSettings[0] > 0);
            onView(withId(R.id.settings)).perform(click());
            back();
            await(reopened,state -> state.screen() == Screen.VOCABULARY && state.savedCount() == 33,"list after settings");
            onView(withId(R.id.scroll)).check((view,error) -> {
                if (error != null) throw error;
                assertEquals(scrollThroughSettings[0],view.getScrollY());
            });
            onView(withText(savedLabel)).perform(scrollTo(),click());
            int[] scrollBefore = new int[1];
            reopened.onActivity(activity -> scrollBefore[0] = activity.findViewById(R.id.scroll).getScrollY());
            assertTrue("saved row below the fold",scrollBefore[0] > 0);
            onView(withText(actions[0])).perform(click());
            NavigationTest.awaitDictionary(reopened);
            reopened.recreate();
            NavigationTest.awaitDictionary(reopened);
            back();
            await(reopened,state -> state.screen() == Screen.VOCABULARY && state.savedCount() == 33,"list restored");
            onView(withId(R.id.scroll)).check((view,error) -> {
                if (error != null) throw error;
                assertEquals(scrollBefore[0],view.getScrollY());
            });
        }
    }

    static void capture(ActivityScenario<MainActivity> scenario,String name) throws IOException {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("lexiflowEvidence"))) return;
        Bitmap[] frame = new Bitmap[1];
        scenario.onActivity(activity -> {
            android.view.View root = activity.findViewById(R.id.root);
            frame[0] = Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
            var canvas = new Canvas(frame[0]);
            var theme = activity.obtainStyledAttributes(new int[]{android.R.attr.windowBackground});
            try {
                var background = theme.getDrawable(0);
                if (background != null) {
                    background.setBounds(0,0,root.getWidth(),root.getHeight());
                    background.draw(canvas);
                }
            } finally {
                theme.recycle();
            }
            root.draw(canvas);
        });
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File evidence = new File(context.getCacheDir(),"lexiflow-evidence");
        try {
            if (!evidence.isDirectory() && !evidence.mkdir()) throw new IOException("cannot create evidence directory");
            try (var output = new FileOutputStream(new File(evidence,name+".png"))) {
                if (!frame[0].compress(Bitmap.CompressFormat.PNG,100,output)) throw new IOException("cannot save app frame");
            }
        } finally {
            frame[0].recycle();
        }
    }

    static void await(ActivityScenario<MainActivity> scenario,
                              Predicate<FoundationViewModel.PersonalView> condition,String label) throws InterruptedException {
        var ready = new CountDownLatch(1);
        Observer<FoundationViewModel.PersonalView> observer = value -> {
            if (value != null && condition.test(value)) ready.countDown();
        };
        scenario.onActivity(activity -> new ViewModelProvider(activity).get(FoundationViewModel.class)
                .personalView.observeForever(observer));
        try {
            assertTrue(label,ready.await(8,TimeUnit.SECONDS));
        } finally {
            scenario.onActivity(activity -> new ViewModelProvider(activity).get(FoundationViewModel.class)
                    .personalView.removeObserver(observer));
        }
    }

    private static void back() { onView(withContentDescription(R.string.back)).perform(click()); }
    private static void action(int label) { onView(withText(label)).perform(scrollTo(),click()); }
    private static void savedAction(String label,String action) {
        onView(withText(label)).perform(scrollTo(),click());
        onView(withText(action)).perform(click());
    }
    private static void editCollection(String name) {
        onView(withHint(R.string.collection_name)).perform(replaceText(name),closeSoftKeyboard());
        onView(withText(R.string.save)).perform(click());
    }
}
