package app.lexiflow.ui;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.lexiflow.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class NavigationTest {
    @Test public void launchShowsEmptyDictionaryAndTabsSwitchSections() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.title)).check(matches(withText(R.string.dictionary)));
            onView(withId(R.id.panel_title)).check(matches(withText(R.string.no_corpus)));
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

    @Test public void returningFromRecreatedEntryRestoresQuery() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            String draft = "два слова";
            onView(withId(R.id.query)).perform(click(), replaceText(draft), closeSoftKeyboard());
            onView(withId(R.id.action)).perform(scrollTo(), click());
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
            onView(withId(R.id.panel_title)).check(matches(withText(R.string.no_installed_dictionary)));
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
}
