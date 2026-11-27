package app.lexiflow.ui;

import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.R;
import app.lexiflow.data.LocalDictionaryRepository;
import app.lexiflow.data.PersonalDatabase;
import app.lexiflow.data.ReviewScheduler;
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
public class DesignPreviewTest {
    private static final String HEADWORD = "aurora-demo";
    private static final String COLLECTION = "Демонстрационная подборка с длинным названием";

    @Test public void preparesRepresentativeDesignStatesInFreshIsolatedProfile() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("isolated preview profile required","app.lexiflow.runtimecheck".equals(target.getPackageName())
                && target.getAssets().list("dictionary-design-preview").length > 0);
        String personalName = "lexiflow-personal.sqlite";
        assertFalse(target.getDatabasePath(personalName).exists());
        var repository = new LocalDictionaryRepository(target);
        var personal = Room.databaseBuilder(target,PersonalDatabase.class,personalName).build();
        String previewSense;
        try (var json = target.getAssets().open("dictionary-design-preview/manifest.json");
             var db = target.getAssets().open("dictionary-design-preview/dictionary.sqlite")) {
            repository.install(json,db);
            assertTrue(repository.isSynthetic());
            var hit = repository.search(HEADWORD,false).stream().filter(row -> "noun".equals(row.pos()))
                    .findFirst().orElseThrow(AssertionError::new);
            previewSense = hit.senseId();
            var article = repository.entry(hit.entryId());
            assertEquals(3,article.senses().size());
            assertEquals(2,article.senses().get(0).translations().size());
            assertEquals(2,article.senses().get(0).examples().size());
            assertEquals(2,article.forms().size());
            var library = personal.library();
            var collection = new PersonalDatabase.Collection();
            collection.name = COLLECTION;
            long collectionId = library.create(collection);
            long now = System.currentTimeMillis();
            for (String key : new String[]{HEADWORD,"biv","paper lantern cue",
                    "illustrative-snow-globe-with-an-extended-label"}) {
                var selected = key.equals(HEADWORD) ? hit : repository.search(key,false).get(0);
                var row = new PersonalDatabase.Saved();
                row.entryId = selected.entryId();
                row.senseId = selected.senseId();
                row.spelling = selected.spelling();
                row.answer = selected.translation();
                if (key.equals(HEADWORD)) {
                    row.custom = "Личный вариант для макета";
                    row.note = "Первая строка заметки.\nВторая строка для проверки переноса.";
                }
                long saved = library.save(row);
                var member = new PersonalDatabase.Member();
                member.collectionId = collectionId;
                member.savedId = saved;
                library.addMember(member);
                var card = new PersonalDatabase.Card();
                card.savedId = saved;
                card.due = now;
                card.reverse = key.equals(HEADWORD);
                library.addCard(card);
            }
            assertTrue(library.grade(library.next(now),2,now,ReviewScheduler::basic));
        } finally {
            personal.close();
            repository.close();
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            NavigationTest.awaitDictionary(scenario);
            capture(scenario,"01-dictionary");
            onView(withId(R.id.query)).perform(click(),replaceText(HEADWORD),closeSoftKeyboard());
            NavigationTest.awaitDictionary(scenario);
            capture(scenario,"02-results");
            onView(withTagValue(org.hamcrest.Matchers.is((Object)previewSense))).perform(scrollTo(),click());
            NavigationTest.awaitDictionary(scenario);
            capture(scenario,"03-entry");
            onView(withContentDescription(R.string.back)).perform(click());
            onView(withId(R.id.vocabulary)).perform(click());
            await(scenario,state -> state.screen() == Screen.VOCABULARY && state.savedCount() == 4,"preview library");
            capture(scenario,"04-vocabulary");
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen() == Screen.COLLECTIONS,"preview collections");
            onView(withText(COLLECTION)).perform(scrollTo(),click());
            await(scenario,state -> state.collection() != 0 && state.saved().size() == 4,"preview collection members");
            capture(scenario,"05-collection");
            onView(withContentDescription(R.string.back)).perform(click());
            onView(withContentDescription(R.string.back)).perform(click());
            onView(withId(R.id.review)).perform(click());
            await(scenario,state -> state.screen() == Screen.REVIEW && state.learning().unstudied > 0,"preview learning");
            onView(withText(startsWith("Учить новые слова"))).perform(scrollTo(),click());
            await(scenario,state -> state.screen() == Screen.STUDY && state.exercise() != null,"preview card");
            capture(scenario,"06-review-front");
            onView(withText(R.string.reveal)).perform(scrollTo(),click());
            capture(scenario,"07-review-answer");
            onView(withContentDescription(R.string.back)).perform(click());
            onView(withId(R.id.progress)).perform(click());
            await(scenario,state -> state.screen() == Screen.PROGRESS && state.reviewed() == 1,"preview statistics");
            capture(scenario,"08-progress");
            onView(withId(R.id.settings)).perform(click());
            NavigationTest.awaitDictionary(scenario);
            capture(scenario,"09-settings");
            onView(withContentDescription(R.string.back)).perform(click());
            onView(withId(R.id.search)).perform(click());
            NavigationTest.awaitDictionary(scenario);
            onView(withTagValue(org.hamcrest.Matchers.is((Object)previewSense))).perform(scrollTo(),click());
            NavigationTest.awaitDictionary(scenario);
            onView(withText(R.string.word_in_vocabulary)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
        }
    }
}
