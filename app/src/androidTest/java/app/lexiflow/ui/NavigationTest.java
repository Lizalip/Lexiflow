package app.lexiflow.ui;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.appbar.MaterialToolbar;
import app.lexiflow.R;
import app.lexiflow.data.DictionaryRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public class NavigationTest {
    @Test public void measureSearchDebounceThroughResultLayout() throws InterruptedException {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitDictionary(scenario);
            boolean[] synthetic = {false};
            scenario.onActivity(activity -> {
                var view = new ViewModelProvider(activity).get(FoundationViewModel.class).dictionaryView.getValue();
                synthetic[0] = view != null && view.synthetic();
            });
            org.junit.Assume.assumeTrue("synthetic debug dictionary required",synthetic[0]);
            long[] elapsed = new long[3];
            String[] keys = {"zeta-demo","zeta-demos","zeta"};
            for (int i = 0; i < keys.length; i++) {
                String key = keys[i];
                int index = i;
                CountDownLatch ready = new CountDownLatch(1);
                scenario.onActivity(activity -> {
                    var model = new ViewModelProvider(activity).get(FoundationViewModel.class);
                    var root = activity.findViewById(R.id.root);
                    long start = android.os.SystemClock.elapsedRealtime();
                    root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                        @Override public boolean onPreDraw() {
                            var view = model.dictionaryView.getValue();
                            if (view != null && view.status() == DictionaryRepository.Status.AVAILABLE
                                    && !view.hits().isEmpty()) {
                                root.getViewTreeObserver().removeOnPreDrawListener(this);
                                elapsed[index] = android.os.SystemClock.elapsedRealtime()-start;
                                ready.countDown();
                            }
                            return true;
                        }
                    });
                    ((android.widget.EditText) activity.findViewById(R.id.query)).setText(key);
                });
                assertTrue("search results rendered",ready.await(5,TimeUnit.SECONDS));
            }
            java.util.Arrays.sort(elapsed);
            android.util.Log.i("LexiFlowReceipt","search UI through pre-draw ms="+java.util.Arrays.toString(elapsed));
        }
    }

    @Test public void launchShowsDictionaryStateAndTabsSwitchSections() throws InterruptedException {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.title)).check(matches(withText(R.string.dictionary)));
            onView(withId(R.id.navigation)).check(matches(isDisplayed()));
            assertToolbarIcon(scenario,false);
            awaitDictionary(scenario);
            onView(withId(R.id.panel_title)).check(matches(anyOf(
                    withText(R.string.no_corpus),withText(R.string.dictionary_prompt))));
            onView(withId(R.id.query)).check(matches(isDisplayed()));
            onView(withId(R.id.vocabulary)).perform(click());
            onView(withId(R.id.title)).check(matches(withText(R.string.vocabulary)));
            onView(withId(R.id.review)).perform(click());
            onView(withId(R.id.title)).check(matches(withText(R.string.review)));
            onView(withId(R.id.search)).perform(click());
            onView(withId(R.id.title)).check(matches(withText(R.string.dictionary)));
        }
    }

    @Test public void clearButtonRemovesTypedQuery() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            String draft = "Ёж — it's time";
            onView(withId(R.id.query)).perform(click(), replaceText(draft), closeSoftKeyboard());
            onView(withId(R.id.query)).check(matches(withText(draft)));
            onView(allOf(withId(com.google.android.material.R.id.text_input_end_icon),
                    isDescendantOfA(withId(R.id.query_box)))).perform(click());
            onView(withId(R.id.query)).check(matches(withText("")));
        }
    }

    @Test public void reverseSearchOpensSenseAndRestoresResultsOnBack() throws InterruptedException {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitDictionary(scenario);
            boolean[] synthetic = {false};
            scenario.onActivity(activity -> {
                var view = new ViewModelProvider(activity).get(FoundationViewModel.class).dictionaryView.getValue();
                synthetic[0] = view != null && view.synthetic();
            });
            org.junit.Assume.assumeTrue("synthetic debug dictionary required",synthetic[0]);
            onView(withId(R.id.direction)).perform(click()).check(matches(withText(R.string.reverse_search)));
            String draft = "ДЕМО-ОБЪЕКТ";
            onView(withId(R.id.query)).perform(click(),replaceText(draft),closeSoftKeyboard());
            awaitDictionary(scenario);
            var result = withText(startsWith("zeta-demo · noun · Значение 1"));
            onView(result).perform(scrollTo(),click());
            awaitDictionary(scenario);
            onView(withId(R.id.title)).check(matches(withText(R.string.entry)));
            onView(withId(R.id.panel_title)).check(matches(withText("zeta-demo")));
            onView(withContentDescription(R.string.back)).perform(click());
            awaitDictionary(scenario);
            onView(withId(R.id.query)).check(matches(withText(draft)));
            onView(result).check(matches(isDisplayed()));
        }
    }

    @Test public void returningFromRecreatedEntryRestoresQuery() throws InterruptedException {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitDictionary(scenario);
            boolean[] available = {false};
            scenario.onActivity(activity -> available[0] = new ViewModelProvider(activity)
                    .get(FoundationViewModel.class).hasDictionary());
            String draft = available[0] ? "zeta demo phrase" : "два слова";
            onView(withId(R.id.query)).perform(click(), replaceText("outdated search"), replaceText(draft), closeSoftKeyboard());
            awaitDictionary(scenario);
            if (available[0]) {
                onView(withText(startsWith("zeta demo phrase · phrase · Значение 1"))).perform(scrollTo(),click());
            } else {
                onView(withId(R.id.action)).perform(scrollTo(), click());
            }
            scenario.recreate();
            onView(withId(R.id.title)).check(matches(withText(R.string.entry)));
            onView(withContentDescription(R.string.back)).perform(click());
            onView(withId(R.id.title)).check(matches(withText(R.string.dictionary)));
            onView(withId(R.id.query)).check(matches(withText(draft)));
        }
    }

    @Test public void settingsReturnsToProgressThenBackToReview() throws InterruptedException {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.review)).perform(click());
            assertToolbarIcon(scenario,false);
            onView(withId(R.id.action)).perform(scrollTo(), click());
            onView(withId(R.id.title)).check(matches(withText(R.string.progress)));
            assertToolbarIcon(scenario,true);
            onView(withId(R.id.settings)).perform(click());
            onView(withId(R.id.title)).check(matches(withText(R.string.settings)));
            assertToolbarIcon(scenario,true);
            awaitDictionary(scenario);
            onView(withId(R.id.panel_title)).check(matches(anyOf(
                    withText(R.string.no_installed_dictionary),withText(R.string.dictionary_available))));
            onView(withContentDescription(R.string.back)).perform(click());
            onView(withId(R.id.title)).check(matches(withText(R.string.progress)));
            assertToolbarIcon(scenario,true);
            onView(withContentDescription(R.string.back)).perform(click());
            onView(withId(R.id.title)).check(matches(withText(R.string.review)));
            assertToolbarIcon(scenario,false);
            onView(withId(R.id.navigation)).check(matches(isDisplayed()));
        }
    }

    private static void assertToolbarIcon(ActivityScenario<MainActivity> scenario, boolean expected) {
        scenario.onActivity(activity -> {
            MaterialToolbar toolbar = activity.findViewById(R.id.toolbar);
            if (expected) {
                assertNotNull(toolbar.getNavigationIcon());
            } else {
                assertNull(toolbar.getNavigationIcon());
            }
        });
    }

    @Test public void collectionsSurvivesRecreationAndReturnsToVocabulary() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.vocabulary)).perform(click());
            onView(withId(R.id.action)).perform(scrollTo(), click());
            scenario.recreate();
            onView(withId(R.id.title)).check(matches(withText(R.string.collections)));
            pressBack();
            onView(withId(R.id.title)).check(matches(withText(R.string.vocabulary)));
        }
    }

    static void awaitDictionary(ActivityScenario<MainActivity> scenario) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        androidx.lifecycle.Observer<FoundationViewModel.DictionaryView> observer = state -> {
            if (state != null && state.status() != DictionaryRepository.Status.LOADING) ready.countDown();
        };
        scenario.onActivity(activity -> new ViewModelProvider(activity).get(FoundationViewModel.class)
                .dictionaryView.observeForever(observer));
        try {
            assertTrue("dictionary settled",ready.await(5,TimeUnit.SECONDS));
        } finally {
            scenario.onActivity(activity -> new ViewModelProvider(activity).get(FoundationViewModel.class)
                    .dictionaryView.removeObserver(observer));
        }
    }
}
