package app.lexiflow.ui;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.lexiflow.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class NavigationTest {
    @Test public void collectionsSurvivesRecreationAndReturnsToVocabulary() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.vocabulary)).perform(click());
            onView(withId(R.id.action)).perform(click());
            scenario.recreate();
            onView(withId(R.id.title)).check(matches(withText(R.string.collections)));
            pressBack();
            onView(withId(R.id.title)).check(matches(withText(R.string.vocabulary)));
        }
    }
}
