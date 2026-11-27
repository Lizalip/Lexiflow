package app.lexiflow.ui;

import androidx.lifecycle.SavedStateHandle;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class FoundationViewModelTest {
    @Test public void emptyInstallationStartsInDictionary() {
        FoundationViewModel model = new FoundationViewModel(new SavedStateHandle());
        assertEquals(Screen.SEARCH, model.screen());
        assertEquals("", model.query());
        assertFalse(model.hasDictionary());
    }

    @Test public void settingsReturnsToEachDetailThenItsSection() {
        for (Screen root : new Screen[]{Screen.SEARCH, Screen.VOCABULARY}) {
            FoundationViewModel model = new FoundationViewModel(new SavedStateHandle());
            model.open(root);
            model.open(root.detail());
            model.open(Screen.SETTINGS);
            model.back();
            assertEquals(root.detail(), model.screen());
            model.back();
            assertEquals(root, model.screen());
            model.back();
            assertEquals(Screen.SEARCH, model.screen());
        }
    }

    @Test public void recreatedModelReadsDraftAndRouteFromSavedState() {
        SavedStateHandle state = new SavedStateHandle();
        FoundationViewModel model = new FoundationViewModel(state);
        model.setQuery("Ё — EN");
        model.open(Screen.ENTRY);
        model.open(Screen.SETTINGS);
        FoundationViewModel restored = restore(state);
        assertEquals(Screen.SETTINGS, restored.screen());
        restored.back();
        assertEquals(Screen.ENTRY, restored.screen());
        assertEquals("Ё — EN", restored.query());
    }

    @Test public void eachSectionOpensItsOwnDetail() {
        assertEquals(Screen.ENTRY, Screen.SEARCH.detail());
        assertEquals(Screen.COLLECTIONS, Screen.VOCABULARY.detail());
        assertEquals(Screen.PROGRESS, Screen.REVIEW.detail());
    }

    @Test public void switchingSectionsPreservesDraftAndBackReturnsToSearch() {
        FoundationViewModel model = new FoundationViewModel(new SavedStateHandle());
        String draft = "Ёж — it's time";
        model.setQuery(draft);
        model.open(Screen.VOCABULARY);
        assertEquals(Screen.VOCABULARY, model.screen());
        model.open(Screen.REVIEW);
        assertEquals(Screen.REVIEW, model.screen());
        model.back();
        assertEquals(Screen.SEARCH, model.screen());
        assertEquals(draft, model.query());
    }

    @Test public void settingsReturnsToTheSectionThatOpenedIt() {
        FoundationViewModel model = new FoundationViewModel(new SavedStateHandle());
        for (Screen section : new Screen[]{Screen.SEARCH, Screen.VOCABULARY, Screen.REVIEW, Screen.PROGRESS}) {
            model.open(section);
            model.open(Screen.SETTINGS);
            assertEquals(Screen.SETTINGS, model.screen());
            model.back();
            assertEquals(section, model.screen());
        }
    }

    @Test public void clearedQueryStaysEmptyOnReturnToSearch() {
        FoundationViewModel model = new FoundationViewModel(new SavedStateHandle());
        model.setQuery("draft");
        assertEquals("draft", model.query());
        model.setQuery("");
        model.open(Screen.ENTRY);
        model.back();
        assertEquals("", model.query());
    }

    @Test public void savedQueryKeepsItsOwnDraftAndTreatsGlobSyntaxLiterally() {
        SavedStateHandle state = new SavedStateHandle();
        FoundationViewModel model = new FoundationViewModel(state);
        model.setQuery("dictionary");
        model.setSavedQuery("  Ё[*]?  ");
        model.open(Screen.VOCABULARY);
        model.open(Screen.COLLECTIONS);
        model.back();
        FoundationViewModel restored = new FoundationViewModel(state);
        assertEquals("dictionary",restored.query());
        assertEquals("  Ё[*]?  ",restored.savedQuery());
        assertEquals("*[ёЁ][[][*]][?]*",FoundationViewModel.savedPattern(restored.savedQuery()));
        assertNull(FoundationViewModel.savedPattern(" "));
        restored.setSavedQuery("");
        assertEquals("",new FoundationViewModel(state).savedQuery());
    }

    @Test public void revealedAnswerBelongsToOneCardAtOneScheduledTime() {
        SavedStateHandle state = new SavedStateHandle();
        FoundationViewModel model = new FoundationViewModel(state);
        var card = new app.lexiflow.data.PersonalDatabase.Exercise();
        card.id = 12;
        card.due = 100;
        assertFalse(model.answerShown(card));
        model.revealAnswer(card);
        FoundationViewModel restored = new FoundationViewModel(state);
        assertTrue(restored.answerShown(card));
        card.id = 13;
        assertFalse(restored.answerShown(card));
        card.id = 12;
        card.due = 200;
        assertFalse(restored.answerShown(card));
    }

    @Test public void freshModelRestoresUnfinishedReviewAndPreviousSearchPosition() {
        SavedStateHandle state = new SavedStateHandle();
        state.set("studyQueue",new long[]{9,11,15});
        state.set("studyPosition",1);
        FoundationViewModel model = new FoundationViewModel(state);
        model.setQuery("demo");
        model.setSearchScroll(180);
        model.open(Screen.STUDY);
        model.recordReviewEvent(2);
        model.recordReviewEvent(0);
        var card = new app.lexiflow.data.PersonalDatabase.Exercise();
        card.id = 11;
        card.due = 150;
        model.revealAnswer(card);
        FoundationViewModel restored = restore(state);
        assertEquals(Screen.STUDY,restored.screen());
        assertEquals(1,restored.studyPosition());
        assertEquals(3,restored.studyTotal());
        assertTrue(restored.canResumeStudy());
        assertEquals(2,restored.sessionReviewed());
        assertEquals(1,restored.sessionSuccessful());
        assertTrue(restored.answerShown(card));
        restored.back();
        assertEquals(Screen.REVIEW,restored.screen());
        restored.back();
        assertEquals(Screen.SEARCH,restored.screen());
        assertEquals("demo",restored.query());
        assertEquals(180,restored.searchScroll());
    }

    private static FoundationViewModel restore(SavedStateHandle state) {
        Map<String,Object> snapshot = new HashMap<>();
        for (String key : state.keys()) snapshot.put(key,state.get(key));
        return new FoundationViewModel(new SavedStateHandle(snapshot));
    }
}
