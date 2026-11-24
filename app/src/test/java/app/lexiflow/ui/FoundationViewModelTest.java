package app.lexiflow.ui;

import androidx.lifecycle.SavedStateHandle;
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
        for (Screen root : new Screen[]{Screen.SEARCH, Screen.VOCABULARY, Screen.REVIEW}) {
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
        FoundationViewModel restored = new FoundationViewModel(state);
        assertEquals(Screen.SETTINGS, restored.screen());
        restored.back();
        assertEquals(Screen.ENTRY, restored.screen());
        assertEquals("Ё — EN", restored.query());
    }
}
