package app.lexiflow.ui;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.ViewModelProvider;
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
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class NavigationTest {
    @Test public void launchShowsDictionaryStateAndTabsSwitchSections() throws InterruptedException {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.title)).check(matches(withText(R.string.dictionary)));
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
            onView(withId(com.google.android.material.R.id.text_input_end_icon)).perform(click());
            onView(withId(R.id.query)).check(matches(withText("")));
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
                onView(withText("zeta demo phrase · phrase · Значение 1")).perform(scrollTo(),click());
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

    @Test public void settingsReturnsToProgressThenBackToReview() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.review)).perform(click());
            onView(withId(R.id.action)).perform(scrollTo(), click());
            onView(withId(R.id.title)).check(matches(withText(R.string.progress)));
            onView(withId(R.id.settings)).perform(click());
            onView(withId(R.id.title)).check(matches(withText(R.string.settings)));
            onView(withId(R.id.panel_title)).check(matches(anyOf(
                    withText(R.string.no_installed_dictionary),withText(R.string.no_settings))));
            pressBack();
            onView(withId(R.id.title)).check(matches(withText(R.string.progress)));
            pressBack();
            onView(withId(R.id.title)).check(matches(withText(R.string.review)));
        }
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

    private static void awaitDictionary(ActivityScenario<MainActivity> scenario) throws InterruptedException {
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
