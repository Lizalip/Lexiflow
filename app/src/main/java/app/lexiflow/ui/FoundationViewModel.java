package app.lexiflow.ui;

import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import app.lexiflow.data.DictionaryRepository;
import app.lexiflow.data.LocalDictionaryRepository;

public final class FoundationViewModel extends ViewModel {
    private final SavedStateHandle state;
    private final DictionaryRepository dictionary = new LocalDictionaryRepository();

    public FoundationViewModel(SavedStateHandle state) {
        this.state = state;
    }

    boolean hasDictionary() {
        return !dictionary.installedLanguages().isEmpty();
    }

    Screen screen() {
        return savedScreen("screen");
    }

    private Screen savedScreen(String key) {
        String name = state.get(key);
        return name == null ? Screen.SEARCH : Screen.valueOf(name);
    }

    void open(Screen next) {
        if (next == Screen.SETTINGS && screen() != Screen.SETTINGS) {
            state.set("settingsOrigin", screen().name());
        }
        state.set("screen", next.name());
    }

    void back() {
        Screen current = screen();
        if (current == Screen.SETTINGS) {
            open(savedScreen("settingsOrigin"));
        } else {
            open(current.parent() == current ? Screen.SEARCH : current.parent());
        }
    }

    String query() {
        String draft = state.get("query");
        return draft == null ? "" : draft;
    }

    void setQuery(String query) {
        state.set("query", query);
    }
}
