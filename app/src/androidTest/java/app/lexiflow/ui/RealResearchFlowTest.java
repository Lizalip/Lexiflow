package app.lexiflow.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import androidx.lifecycle.ViewModelProvider;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.R;
import app.lexiflow.data.DictionaryRepository;
import app.lexiflow.data.LocalDictionaryRepository;
import app.lexiflow.data.PersonalDatabase;
import app.lexiflow.data.ReviewScheduler;
import java.io.File;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;
import static app.lexiflow.ui.IsolatedPersonalFlowTest.await;
import static app.lexiflow.ui.IsolatedPersonalFlowTest.capture;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.containsString;
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
            onView(withId(R.id.research_banner)).check(matches(withText(containsString(view(scenario).corpusVersion()))));
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
                    +"\n"+TranslationDisplay.wording(hit.translation());
            onView(withText(label)).check(matches(isDisplayed()));
            Log.i(TAG,"book input with debounce and rendered match ms="+(SystemClock.elapsedRealtime()-typed));
            capture(scenario,"02-results");
            onView(withText(label)).perform(scrollTo(),click());
            awaitAvailable(scenario);
            var article = view(scenario).entry();
            assertNotNull(article);
            assertTrue(article.senses().size()>1);
            var ipa = article.metadata().stream().filter(row -> row.kind().equals("ipa")).findFirst().orElseThrow();
            onView(withText(target.getString(R.string.metadata_line,target.getString(R.string.metadata_ipa),
                    ipa.value(),ipa.attribution()))).check(matches(isDisplayed()));
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

    @Test public void opensAdvancedCorpusAndMeasuresSearch() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var target = instrumentation.getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        String expected = InstrumentationRegistry.getArguments().getString("lexiflowCorpus");
        assumeTrue(expected!=null);
        long start = SystemClock.elapsedRealtime();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitDictionary(scenario,state -> state.status()!=DictionaryRepository.Status.LOADING,
                    "advanced dictionary settled",120);
            assertEquals(DictionaryRepository.Status.AVAILABLE,view(scenario).status());
            assertEquals(expected,view(scenario).corpusVersion());
            logCapacity(target,start);
            query("book");
            awaitDictionary(scenario,state -> state.hits().stream().anyMatch(hit -> "book".equals(hit.spelling())),
                    "English search returned the original article");
        }
        measureRepository(target);
    }

    @Test public void learnsAnIndependentRussianSenseAfterUpdatingEnglishCorpus() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        String word = InstrumentationRegistry.getArguments().getString("lexiflowDirectWord");
        assumeTrue(word!=null);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitDictionary(scenario,state -> state.status()!=DictionaryRepository.Status.LOADING,"bilingual loaded",100);
            assertEquals(DictionaryRepository.Status.AVAILABLE,view(scenario).status());
            onView(withId(R.id.direction)).perform(click());
            query(word);
            awaitDictionary(scenario,state -> state.hits().stream().anyMatch(hit -> "ru".equals(hit.language())
                    && word.equals(hit.spelling())),"direct Russian source found");
            var hit = view(scenario).hits().get(0);
            assertEquals(word,hit.spelling());
            assertEquals("ru",hit.language());
            assertNotNull(hit.translation());
            String label=hit.spelling()+" · "+hit.pos()+" · "+target.getString(R.string.sense_title,hit.position())
                    +"\n"+TranslationDisplay.wording(hit.translation())+"\n"+target.getString(R.string.direct_russian_article);
            onView(withText(label)).perform(scrollTo(),click());
            awaitDictionary(scenario,state -> state.entry()!=null,"direct article opened");
            var article = view(scenario).entry();
            assertEquals("ru",article.language());
            assertEquals(word,article.spelling());
            assertTrue(article.attribution().contains("RU–EN"));
            onView(withText(R.string.pronounce_offline)).check(doesNotExist());
            onView(withText(target.getString(R.string.save_sense,hit.position()))).perform(scrollTo(),click());
            back();
            onView(withId(R.id.vocabulary)).perform(click());
            await(scenario,state -> state.savedCount()==2 && state.saved().stream().anyMatch(row ->
                    "book".equals(row.spelling) && CUSTOM.equals(row.custom)),"previous English save survived update");
            String[] actions = target.getResources().getStringArray(R.array.saved_actions);
            onView(withText(startsWith(word+" · "))).perform(scrollTo(),click());
            onView(withText(actions[1])).perform(click());
            onView(withHint(R.string.custom_translation)).perform(replaceText("Local trial gloss"));
            onView(withHint(R.string.note)).perform(replaceText("Local trial note"),closeSoftKeyboard());
            onView(withText(R.string.save)).perform(click());
            await(scenario,state -> state.saved().stream().anyMatch(row -> word.equals(row.spelling)
                    && "Local trial note".equals(row.note)),"Russian note saved");
            onView(withText(startsWith(word+" · "))).perform(scrollTo(),click());
            onView(withText(actions[2])).perform(click());
            onView(withText(COLLECTION)).perform(click());
            for (int index : new int[]{4,3}) {
                onView(withText(startsWith(word+" · "))).perform(scrollTo(),click());
                onView(withText(actions[index])).perform(click());
            }
            onView(withId(R.id.review)).perform(click());
            await(scenario,state -> state.exercise()!=null && !state.exercise().reverse,"direct card unlocked");
            action(R.string.reveal);
            onView(withId(R.id.body)).check(matches(withText("Local trial gloss")));
            String[] grades = target.getResources().getStringArray(R.array.review_ratings);
            onView(withText(grades[1])).perform(scrollTo(),click());
            await(scenario,state -> state.exercise()!=null && state.exercise().reverse,"inverse card unlocked");
            action(R.string.reveal);
            onView(withId(R.id.body)).check(matches(withText(word)));
            onView(withText(grades[2])).perform(scrollTo(),click());
            await(scenario,state -> state.screen()==Screen.REVIEW && state.exercise()==null,"both directions graded");
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen()==Screen.PROGRESS && state.reviewed()==4 && state.cards()==4,
                    "both languages retain review history");
        }
    }

    @Test public void displaysOfficialCefrPerPartOfSpeech() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitDictionary(scenario,state -> state.status()!=DictionaryRepository.Status.LOADING,"CEFR corpus opened",120);
            assertEquals(DictionaryRepository.Status.AVAILABLE,view(scenario).status());
            assertTrue(view(scenario).corpusVersion().endsWith("-cefr-j"));
            query("book");
            awaitDictionary(scenario,state -> state.hits().stream().filter(row -> "book".equals(row.spelling()))
                    .map(DictionaryRepository.Hit::pos).distinct().count()>=2,"English parts of speech available");
            var repository = new LocalDictionaryRepository(target);
            try {
                repository.load();
                var overlapping = repository.search("мир",true);
                assertEquals("ru",overlapping.get(0).language());
                assertTrue(overlapping.stream().filter(row -> "ru".equals(row.language())
                        && "мир".equalsIgnoreCase(row.spelling())).map(DictionaryRepository.Hit::entryId).distinct().count()>=2);
                assertTrue(overlapping.stream().anyMatch(row -> "en".equals(row.language())
                        && "мир".equals(row.translation())));
                assertEquals(overlapping.size(),overlapping.stream().map(DictionaryRepository.Hit::senseId).distinct().count());
                assertTrue(overlapping.size()<=50);
                var reverseOnly = repository.search("книга",true);
                assertTrue(reverseOnly.stream().anyMatch(row -> "en".equals(row.language())
                        && "книга".equals(row.translation())));
                assertTrue(reverseOnly.stream().noneMatch(row -> "ru".equals(row.language())
                        && "книга".equals(row.spelling())));
                assertTrue(repository.search("книг",true).stream().anyMatch(row -> "en".equals(row.language())
                        && "книга".equals(row.translation())));
                assertTrue(repository.search("понимать",true).stream().anyMatch(row -> row.accentMatch()
                        && row.translation().equals("понима\u0301ть")));
                for (var expected : java.util.Map.of("n","A1","v","B1").entrySet()) {
                    var found = repository.search("book",false).stream().filter(row -> "book".equals(row.spelling())
                            && expected.getKey().equals(row.pos())).findFirst().orElseThrow();
                    var article = repository.entry(found.entryId());
                    assertTrue(article.metadata().stream().anyMatch(value -> "cefr".equals(value.kind())
                            && expected.getValue().equals(value.value())));
                    assertTrue(article.senses().stream().flatMap(sense -> sense.metadata().stream())
                            .noneMatch(value -> "cefr".equals(value.kind())));
                }
            } finally {
                repository.close();
            }
            var hit = view(scenario).hits().stream().filter(row -> "book".equals(row.spelling()) && "n".equals(row.pos()))
                    .findFirst().orElseThrow();
            onView(withText("book · n · "+target.getString(R.string.sense_title,hit.position())
                    +"\n"+TranslationDisplay.wording(hit.translation()))).perform(scrollTo(),click());
            awaitDictionary(scenario,state -> state.entry()!=null,"CEFR article displayed");
            onView(withText(containsString("CEFR-J Wordlist Version 1.6"))).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test public void displaysUsageExampleWithSeparateSentenceAndTranslationCredits() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitDictionary(scenario,state -> state.status()!=DictionaryRepository.Status.LOADING,"usage corpus opened",120);
            assertEquals(DictionaryRepository.Status.AVAILABLE,view(scenario).status());
            assertTrue(view(scenario).corpusVersion().endsWith("-usage-pilot"));
            query("chance");
            awaitDictionary(scenario,state -> state.hits().stream().anyMatch(row ->
                    "chance".equals(row.spelling()) && "n".equals(row.pos())),"example article available");
            var hit = view(scenario).hits().stream().filter(row -> "chance".equals(row.spelling()) && "n".equals(row.pos()))
                    .findFirst().orElseThrow();
            onView(withText("chance · n · "+target.getString(R.string.sense_title,hit.position())
                    +"\n"+TranslationDisplay.wording(hit.translation()))).perform(scrollTo(),click());
            awaitDictionary(scenario,state -> state.entry()!=null,"article with example opened");
            var article = view(scenario).entry();
            var usage = article.metadata().stream().filter(row -> "usage_example".equals(row.kind()))
                    .findFirst().orElseThrow();
            assertEquals(2,usage.value().split("\n").length);
            assertEquals(2,usage.attribution().split("tatoeba.org/en/sentences/show/",-1).length-1);
            assertTrue(article.senses().stream().allMatch(row -> row.examples().isEmpty()));
            onView(withText(startsWith(target.getString(R.string.source_usage_example))))
                    .perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test public void opensSeveralAttributedExamplesAndSearchesInflectedForms() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        String expected = InstrumentationRegistry.getArguments().getString("lexiflowCorpus");
        long start = SystemClock.elapsedRealtime();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitDictionary(scenario,state -> state.status()!=DictionaryRepository.Status.LOADING,"rich corpus opened",120);
            assertEquals(DictionaryRepository.Status.AVAILABLE,view(scenario).status());
            if (expected==null) assertTrue(view(scenario).corpusVersion().endsWith("-usage-v2-ud-forms"));
            else {
                assertEquals(expected,view(scenario).corpusVersion());
                logCapacity(target,start);
            }
            query("chance");
            awaitDictionary(scenario,state -> state.hits().stream().anyMatch(row ->
                    "chance".equals(row.spelling()) && "n".equals(row.pos())),"example owner available");
            var hit = view(scenario).hits().stream().filter(row -> "chance".equals(row.spelling()) && "n".equals(row.pos()))
                    .findFirst().orElseThrow();
            onView(withText("chance · n · "+target.getString(R.string.sense_title,hit.position())
                    +"\n"+TranslationDisplay.wording(hit.translation()))).perform(scrollTo(),click());
            awaitDictionary(scenario,state -> state.entry()!=null,"rich article loaded");
            var article = view(scenario).entry();
            var usage = article.metadata().stream().filter(row -> "usage_example".equals(row.kind()))
                    .collect(java.util.stream.Collectors.toList());
            assertTrue(usage.size()>=2);
            assertTrue(usage.stream().allMatch(example ->
                    example.attribution().split("tatoeba.org/en/sentences/show/",-1).length==3));
            assertTrue(article.senses().stream().allMatch(row -> row.examples().isEmpty()));
            onView(withText(startsWith(target.getString(R.string.source_usage_example)+" · "+usage.get(0).value())))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            capture(scenario,"13-attributed-usage");
            back();
            query("went");
            awaitDictionary(scenario,state -> state.hits().stream().anyMatch(row ->
                    "go".equals(row.spelling()) && "went".equals(row.matchedForm())),"inflected English query");
            var found = view(scenario).hits().stream().filter(row -> "go".equals(row.spelling())
                    && "went".equals(row.matchedForm())).findFirst().orElseThrow();
            onView(withText("go · v · "+target.getString(R.string.sense_title,found.position())
                    +"\n"+TranslationDisplay.wording(found.translation())+"\n"+target.getString(R.string.matched_form,"went")))
                    .perform(scrollTo(),click());
            awaitDictionary(scenario,state -> state.entry()!=null,"inflected search opened the lemma");
            assertTrue(view(scenario).entry().forms().stream().anyMatch(row ->
                    "went".equals(row.spelling()) && "Past".equals(row.grammar())));
            onView(withText(containsString(target.getString(R.string.grammar_past))))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            capture(scenario,"14-grammatical-forms");
        }
        if (expected!=null) {
            measureRepository(target);
            var dictionary = new LocalDictionaryRepository(target);
            try {
                dictionary.load();
                measure("form",5,() -> assertTrue(dictionary.search("studying",false).stream()
                        .anyMatch(row -> "study".equals(row.spelling()) && "studying".equals(row.matchedForm()))));
                var displays = dictionary.search("thought",false);
                assertEquals("thought",displays.get(0).spelling());
                assertTrue(displays.stream().anyMatch(row -> "think".equals(row.spelling())
                        && "thought".equals(row.matchedForm())));
            } finally {
                dictionary.close();
            }
        }
    }

    @Test public void readsBothLanguagesAfterRestartOrRollback() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        String word = args.getString("lexiflowDirectWord");
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(word!=null && "app.lexiflow.researchcheck".equals(target.getPackageName()));
        boolean rollback = "true".equals(args.getString("lexiflowRollback"));
        String added = args.getString("lexiflowAddedWord");
        String expectedVersion = args.getString("lexiflowCorpus");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitAvailable(scenario);
            if (expectedVersion!=null) assertEquals(expectedVersion,view(scenario).corpusVersion());
            onView(withId(R.id.vocabulary)).perform(click());
            await(scenario,state -> state.savedCount()==(added==null ? 2 : 3) && state.saved().stream().anyMatch(row ->
                    word.equals(row.spelling) && "Local trial note".equals(row.note)
                    && "Local trial gloss".equals(row.custom)),"both saved languages persisted");
            onView(withText(startsWith(word+" · "))).perform(scrollTo(),click());
            onView(withText(R.string.open_saved)).perform(click());
            awaitDictionary(scenario,state -> state.status()==DictionaryRepository.Status.AVAILABLE,
                    "saved link opened");
            if (rollback && added==null) onView(withText(R.string.entry_unavailable)).check(matches(isDisplayed()));
            else assertEquals(word,view(scenario).entry().spelling());
            back();
            if (added!=null) {
                onView(withText(startsWith(added+" · "))).perform(scrollTo(),click());
                onView(withText(R.string.open_saved)).perform(click());
                if (rollback) onView(withText(R.string.entry_unavailable)).check(matches(isDisplayed()));
                else awaitDictionary(scenario,state -> state.entry()!=null && added.equals(state.entry().spelling()),
                        "expanded Russian article reopened");
                back();
            }
            onView(withId(R.id.action)).perform(scrollTo(),click());
            onView(withText(COLLECTION)).perform(scrollTo(),click());
            await(scenario,state -> state.collection()!=0 && state.saved().size()==2,"collection persisted");
            back();
            back();
            onView(withId(R.id.review)).perform(click());
            onView(withId(R.id.action)).perform(scrollTo(),click());
            await(scenario,state -> state.screen()==Screen.PROGRESS && state.cards()==4 && state.reviewed()==4,
                    "bilingual review history persisted");
        }
    }

    @Test public void savesRussianSenseAfterBilingualExpansion() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var target = instrumentation.getTargetContext();
        var args = InstrumentationRegistry.getArguments();
        String added = args.getString("lexiflowAddedWord");
        String expectedVersion = args.getString("lexiflowCorpus");
        assumeTrue(added!=null && expectedVersion!=null && "app.lexiflow.researchcheck".equals(target.getPackageName()));
        long start = SystemClock.elapsedRealtime();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitDictionary(scenario,state -> state.status()!=DictionaryRepository.Status.LOADING,"expanded package activated",120);
            assertEquals(DictionaryRepository.Status.AVAILABLE,view(scenario).status());
            assertEquals(expectedVersion,view(scenario).corpusVersion());
            logCapacity(target,start);
            onView(withId(R.id.direction)).perform(click());
            long typed = SystemClock.elapsedRealtime();
            query(added);
            awaitDictionary(scenario,state -> !state.hits().isEmpty() && added.equals(state.hits().get(0).spelling()),
                    "new direct Russian sense found");
            var hit = view(scenario).hits().get(0);
            assertEquals("ru",hit.language());
            String label = hit.spelling()+" · "+hit.pos()+" · "+target.getString(R.string.sense_title,hit.position())
                    +"\n"+TranslationDisplay.wording(hit.translation())+"\n"+target.getString(R.string.direct_russian_article);
            onView(withText(label)).check(matches(isDisplayed()));
            Log.i(TAG,"new Russian input to visible match ms="+(SystemClock.elapsedRealtime()-typed));
            capture(scenario,"11-expanded-russian-results");
            onView(withText(label)).perform(scrollTo(),click());
            awaitDictionary(scenario,state -> state.entry()!=null && hit.entryId().equals(state.entry().id()),
                    "expanded article opened");
            capture(scenario,"12-expanded-russian-entry");
            onView(withText(target.getString(R.string.save_sense,hit.position()))).perform(scrollTo(),click());
            back();
            onView(withId(R.id.vocabulary)).perform(click());
            await(scenario,state -> state.savedCount()==3 && state.saved().stream()
                    .anyMatch(row -> hit.senseId().equals(row.senseId)),"expanded Russian sense saved");
        }
        measureRepository(target);
    }

    @Test public void resolvesUnaccentedRussianQueriesThroughTheResearchIndex() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        var dictionary = new LocalDictionaryRepository(target);
        try {
            dictionary.load();
            assertEquals(DictionaryRepository.Status.AVAILABLE,dictionary.status());
            var literal = dictionary.search("понима\u0301ть",true);
            measure("stress-fallback",5,() -> {
                var hits = dictionary.search("  ПОНИМАТЬ   ",true);
                assertTrue(hits.stream().anyMatch(row -> row.spelling().equals("understand")
                        && row.translation().equals("понима\u0301ть")));
                assertTrue(hits.stream().allMatch(row -> literal.stream()
                    .anyMatch(exact -> exact.senseId().equals(row.senseId()))));
            });
            assertTrue(dictionary.search("устойчивый",true).stream()
                    .anyMatch(row -> row.spelling().equals("steady")));
            assertFalse(dictionary.search("книга",true).isEmpty());
        } finally {
            dictionary.close();
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitAvailable(scenario);
            onView(withId(R.id.direction)).perform(click());
            long typed = SystemClock.elapsedRealtime();
            query("ПОНИМАТЬ");
            awaitDictionary(scenario,state -> state.hits().stream().anyMatch(row -> row.spelling().equals("understand")),
                    "unaccented Russian query returned a result");
            Log.i(TAG,"Russian input with debounce and rendered match ms="+(SystemClock.elapsedRealtime()-typed));
            capture(scenario,"10-reverse-results");
            onView(withId(R.id.direction)).perform(click());
            query("light");
            awaitDictionary(scenario,state -> state.hits().stream().filter(row -> row.spelling().equals("light"))
                    .map(DictionaryRepository.Hit::pos).distinct().count()>=2,"multiple parts of speech available");
            capture(scenario,"11-multiple-pos");
        }
    }

    @Test public void seedAdditionalUpgradeSaves() {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        var dictionary = new LocalDictionaryRepository(target);
        var personal = Room.databaseBuilder(target,PersonalDatabase.class,"lexiflow-personal.sqlite").build();
        try {
            dictionary.load();
            assertFalse(dictionary.search("hypothesis",false).stream().anyMatch(hit -> hit.spelling().equals("hypothesis")));
            var library = personal.library();
            assertEquals(1,library.savedCount());
            var collection = library.collections().get(0);
            for (String spelling : new String[]{"apple","house"}) {
                var hit = dictionary.search(spelling,false).stream().filter(row -> row.spelling().equals(spelling))
                        .findFirst().orElseThrow();
                var saved = new PersonalDatabase.Saved();
                saved.entryId = hit.entryId();
                var article = dictionary.entry(hit.entryId());
                var sense = article.senses().stream().filter(row -> row.id().equals(hit.senseId())).findFirst().orElseThrow();
                if ("house".equals(spelling)) saved.senseId = hit.senseId();
                saved.spelling = hit.spelling();
                saved.answer = saved.senseId.isEmpty() ? String.join(" · ",article.senses().stream()
                        .flatMap(row -> row.translations().stream()).distinct().collect(java.util.stream.Collectors.toList()))
                        : String.join(" · ",sense.translations());
                saved.custom = CUSTOM;
                saved.note = NOTE;
                var membership = new PersonalDatabase.Member();
                membership.savedId = library.save(saved);
                membership.collectionId = collection.id;
                assertTrue(library.addMember(membership)>0);
            }
        } finally {
            personal.close();
            dictionary.close();
        }
    }

    @Test public void seedMeaningAvailableOnlyInExpandedCorpus() {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        var dictionary = new LocalDictionaryRepository(target);
        var personal = Room.databaseBuilder(target,PersonalDatabase.class,"lexiflow-personal.sqlite").build();
        try {
            dictionary.load();
            var hit = dictionary.search("hypothesis",false).stream()
                    .filter(row -> "hypothesis".equals(row.spelling())).findFirst().orElseThrow();
            var saved = new PersonalDatabase.Saved();
            saved.entryId = hit.entryId();
            saved.senseId = hit.senseId();
            saved.spelling = hit.spelling();
            saved.answer = hit.translation();
            saved.custom = CUSTOM;
            saved.note = NOTE;
            var library = personal.library();
            long id = library.save(saved);
            assertTrue(id>0);
            long now = System.currentTimeMillis();
            var card = new PersonalDatabase.Card();
            card.savedId = id;
            card.due = now;
            assertTrue(library.addCard(card)>0);
            var next = library.next(now);
            assertNotNull(next);
            assertEquals("hypothesis",next.spelling);
            assertTrue(library.grade(next,2,now,ReviewScheduler::basic));
        } finally {
            personal.close();
            dictionary.close();
        }
    }

    @Test public void preservesLearningWhenAnEntryDisappearsOnRollback() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        var personal = Room.databaseBuilder(target,PersonalDatabase.class,"lexiflow-personal.sqlite").build();
        try {
            var library = personal.library();
            var saved = library.saved().stream().filter(row -> "hypothesis".equals(row.spelling))
                    .findFirst().orElseThrow();
            assertEquals(NOTE,saved.note);
            assertEquals(CUSTOM,saved.custom);
            assertTrue(library.cardCount()>=1);
            assertTrue(library.reviewCount()>=1);
            assertTrue(library.successCount()>=1);
        } finally {
            personal.close();
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitAvailable(scenario);
            onView(withId(R.id.vocabulary)).perform(click());
            await(scenario,state -> state.saved().stream().anyMatch(row -> "hypothesis".equals(row.spelling)),
                    "saved word remains available despite missing article");
            onView(withText(startsWith("hypothesis · "+CUSTOM))).perform(scrollTo(),click());
            onView(withText(R.string.open_saved)).perform(click());
            awaitDictionary(scenario,state -> state.status()==DictionaryRepository.Status.AVAILABLE
                    && state.entry()==null,"smaller dictionary reports an unavailable article");
            onView(withText(R.string.entry_unavailable)).check(matches(isDisplayed()));
            back();
            onView(withText(startsWith("hypothesis · "+CUSTOM))).check(matches(isDisplayed()));
        }
    }

    @Test public void readsExpandedDictionaryAndPreservesLearning() throws Exception {
        var target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("app.lexiflow.researchcheck".equals(target.getPackageName()));
        long launch = SystemClock.elapsedRealtime();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitAvailable(scenario);
            logCapacity(target,launch);
            query("hypothesis");
            awaitDictionary(scenario,state -> state.hits().stream().anyMatch(row -> row.spelling().equals("hypothesis")),
                    "expanded dictionary answers a new query");
            var dictionary = new LocalDictionaryRepository(target);
            var personal = Room.databaseBuilder(target,PersonalDatabase.class,"lexiflow-personal.sqlite").build();
            try {
                dictionary.load();
                var library = personal.library();
                assertEquals(3,library.savedCount());
                assertEquals(1,library.collections().size());
                assertEquals(3,library.collectionSize(library.collections().get(0).id));
                assertEquals(2,library.cardCount());
                assertEquals(2,library.reviewCount());
                assertEquals(2,library.successCount());
                for (var saved : library.saved()) {
                    var article = dictionary.entry(saved.entryId);
                    assertNotNull(article);
                    assertEquals(saved.spelling,article.spelling());
                    assertEquals(CUSTOM,saved.custom);
                    assertEquals(NOTE,saved.note);
                    if (saved.senseId.isEmpty()) {
                        assertEquals(saved.answer,String.join(" · ",article.senses().stream()
                                .flatMap(row -> row.translations().stream()).distinct()
                                .collect(java.util.stream.Collectors.toList())));
                    } else {
                        var sense = article.senses().stream().filter(row -> row.id().equals(saved.senseId))
                                .findFirst().orElseThrow();
                        assertEquals(saved.answer,String.join(" · ",sense.translations()));
                    }
                }
            } finally {
                personal.close();
                dictionary.close();
            }
            onView(withId(R.id.vocabulary)).perform(click());
            await(scenario,state -> state.savedCount()==3,"expanded vocabulary loaded");
            savedAction(target.getResources().getStringArray(R.array.saved_actions)[0]);
            awaitDictionary(scenario,state -> state.entry()!=null && state.entry().spelling().equals("book"),
                    "stored link reopened");
            capture(scenario,"10-upgraded-entry");
        }
        measureRepository(target);
    }

    private static void logCapacity(Context target,long launch) {
        var memory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memory);
        Log.i(TAG,"capacity ready ms="+(SystemClock.elapsedRealtime()-launch)
                +" pss KiB="+memory.getTotalPss()+" installed data bytes="+fileBytes(target.getFilesDir()));
    }

    private static long fileBytes(File file) {
        if (file.isFile()) return file.length();
        var children = file.listFiles();
        if (children == null) return 0;
        return Arrays.stream(children).mapToLong(RealResearchFlowTest::fileBytes).sum();
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
        awaitDictionary(scenario,state -> state.status()!=DictionaryRepository.Status.LOADING,"research dictionary loaded");
        assertEquals(DictionaryRepository.Status.AVAILABLE,view(scenario).status());
        assertFalse(view(scenario).synthetic());
    }

    private static void awaitDictionary(ActivityScenario<MainActivity> scenario,
            Predicate<FoundationViewModel.DictionaryView> condition,String label) throws InterruptedException {
        awaitDictionary(scenario,condition,label,40);
    }

    private static void awaitDictionary(ActivityScenario<MainActivity> scenario,
            Predicate<FoundationViewModel.DictionaryView> condition,String label,int seconds) throws InterruptedException {
        var settled = new CountDownLatch(1);
        androidx.lifecycle.Observer<FoundationViewModel.DictionaryView> observer = value -> {
            if (value!=null && condition.test(value)) settled.countDown();
        };
        scenario.onActivity(activity -> new ViewModelProvider(activity).get(FoundationViewModel.class)
                .dictionaryView.observeForever(observer));
        try {
            assertTrue(label,settled.await(seconds,TimeUnit.SECONDS));
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
