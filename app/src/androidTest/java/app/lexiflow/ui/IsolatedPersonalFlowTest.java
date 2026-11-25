package app.lexiflow.ui;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.R;
import app.lexiflow.data.LocalDictionaryRepository;
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
            onView(withId(R.id.query)).perform(click(),replaceText("zeta-demo"),closeSoftKeyboard());
            NavigationTest.awaitDictionary(scenario);
            onView(withText(startsWith("zeta-demo · noun · Значение 1"))).perform(scrollTo(),click());
            NavigationTest.awaitDictionary(scenario);
            var save = onView(withText(context.getString(R.string.save_sense,1)));
            save.perform(scrollTo(),click(),click());
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
            back();
            back();
            onView(withId(R.id.review)).perform(click());
            await(scenario,state -> state.exercise() != null && !state.exercise().reverse,"forward exercise");
            onView(withId(R.id.panel_title)).check(matches(withText("zeta-demo")));
            action(R.string.reveal);
            onView(withId(R.id.body)).check(matches(withText(custom)));
            onView(withText(ratings[1])).perform(scrollTo(),click());
            await(scenario,state -> state.exercise() != null && state.exercise().reverse,"reverse exercise");
            onView(withId(R.id.panel_title)).check(matches(withText(custom)));
            action(R.string.reveal);
            onView(withId(R.id.body)).check(matches(withText("zeta-demo")));
            onView(withText(ratings[2])).perform(scrollTo(),click());
            await(scenario,state -> state.screen() == Screen.REVIEW && state.exercise() == null,"review queue completed");
            onView(withId(R.id.panel_title)).check(matches(withText(R.string.review_session_finished)));
            onView(withId(R.id.body)).check(matches(withText(context.getString(R.string.review_session_summary,2,2,0))));
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen() == Screen.PROGRESS && state.reviewed() == 2
                    && state.successful() == 2,"reviews counted");
            onView(withId(R.id.settings)).perform(click());
            NavigationTest.awaitDictionary(scenario);
            action(R.string.install_debug_v2);
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
        }
    }

    private static void await(ActivityScenario<MainActivity> scenario,
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
