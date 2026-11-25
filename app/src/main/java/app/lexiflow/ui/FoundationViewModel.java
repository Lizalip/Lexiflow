package app.lexiflow.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.room.Room;
import app.lexiflow.data.DictionaryRepository;
import app.lexiflow.data.LocalDictionaryRepository;
import app.lexiflow.data.PersonalDatabase;
import app.lexiflow.data.ReviewScheduler;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class FoundationViewModel extends ViewModel {
    private final SavedStateHandle state;
    record DictionaryView(DictionaryRepository.Status status, boolean synthetic,
                          List<DictionaryRepository.Hit> hits, DictionaryRepository.Entry entry) {}
    private LocalDictionaryRepository dictionary;
    private PersonalDatabase personal;
    private Handler main;
    private ExecutorService worker;
    private Runnable scheduled;
    private int request;
    final MutableLiveData<DictionaryView> dictionaryView = new MutableLiveData<>();
    record PersonalView(List<PersonalDatabase.Saved> saved, List<PersonalDatabase.Collection> collections,
                        List<PersonalDatabase.Member> memberships, PersonalDatabase.Exercise exercise,
                        int cards, int due, int reviewed, int successful) {}
    final MutableLiveData<PersonalView> personalView = new MutableLiveData<>();
    final MutableLiveData<Boolean> personalError = new MutableLiveData<>();
    private boolean answerShown;

    public FoundationViewModel(SavedStateHandle state) {
        this.state = state;
    }

    boolean hasDictionary() {
        return dictionary != null && dictionary.status() == DictionaryRepository.Status.AVAILABLE;
    }

    void connect(Context context) {
        if (worker != null) return;
        dictionary = new LocalDictionaryRepository(context);
        personal = Room.databaseBuilder(context.getApplicationContext(),PersonalDatabase.class,"lexiflow-personal.sqlite").build();
        main = new Handler(Looper.getMainLooper());
        worker = Executors.newSingleThreadExecutor();
    }

    void refresh(boolean debounce) {
        if (worker == null) return;
        if (scheduled != null) main.removeCallbacks(scheduled);
        final int current = ++request;
        final String text = query();
        final String entryId = state.get("entryId");
        final boolean reverse = reverse();
        final Screen screen = screen();
        if (screen != Screen.SEARCH && screen != Screen.ENTRY && screen != Screen.SETTINGS) {
            refreshPersonal();
            return;
        }
        dictionaryView.setValue(new DictionaryView(DictionaryRepository.Status.LOADING, false, List.of(), null));
        scheduled = () -> worker.execute(() -> {
            DictionaryView result;
            try {
                dictionary.load();
                List<DictionaryRepository.Hit> hits = screen == Screen.SEARCH ? dictionary.search(text,reverse) : List.of();
                DictionaryRepository.Entry entry = screen == Screen.ENTRY && entryId != null ? dictionary.entry(entryId) : null;
                result = new DictionaryView(dictionary.status(),dictionary.isSynthetic(),hits,entry);
            } catch (RuntimeException failure) {
                result = new DictionaryView(DictionaryRepository.Status.ERROR,false,List.of(),null);
            }
            DictionaryView completed = result;
            main.post(() -> { if (current == request) dictionaryView.setValue(completed); });
        });
        main.postDelayed(scheduled, debounce ? 250 : 0);
    }

    boolean reverse() { return Boolean.TRUE.equals(state.get("reverse")); }
    void toggleDirection() { state.set("reverse", !reverse()); refresh(false); }
    String selectedSense() { return state.get("senseId"); }
    void select(String entryId, String senseId) {
        state.set("entryId",entryId);
        state.set("senseId",senseId);
        open(Screen.ENTRY);
    }

    void refreshPersonal() { changePersonal(library -> {}); }

    void changePersonal(Consumer<PersonalDatabase.Library> change) {
        if (worker == null) return;
        worker.execute(() -> {
            try {
                PersonalDatabase.Library library = personal.library();
                change.accept(library);
                long now = System.currentTimeMillis();
                var snapshot = new PersonalView(library.saved(),library.collections(),library.memberships(),
                        library.next(now),library.cardCount(),library.dueCount(now),library.reviewCount(),library.successCount());
                main.post(() -> {
                    answerShown = false;
                    personalError.setValue(false);
                    personalView.setValue(snapshot);
                });
            } catch (RuntimeException failure) {
                main.post(() -> personalError.setValue(true));
            }
        });
    }

    void save(DictionaryRepository.Entry entry, DictionaryRepository.Sense sense) {
        var item = new PersonalDatabase.Saved();
        item.entryId = entry.id();
        item.senseId = sense == null ? "" : sense.id();
        item.spelling = entry.spelling();
        item.answer = sense == null ? String.join(" · ",entry.senses().stream()
                .flatMap(value -> value.translations().stream()).distinct().collect(java.util.stream.Collectors.toList()))
                : String.join(" · ",sense.translations());
        changePersonal(library -> library.save(item));
    }

    long collection() {
        Long id = state.get("collection");
        return id == null ? 0 : id;
    }
    void selectCollection(long id) { state.set("collection",id); }
    boolean answerShown() { return answerShown; }
    void revealAnswer() { answerShown = true; }
    void grade(PersonalDatabase.Exercise exercise,int rating) {
        changePersonal(library -> library.grade(exercise,rating,System.currentTimeMillis(),ReviewScheduler::basic));
    }

    @Override protected void onCleared() {
        if (worker != null) {
            ++request;
            if (scheduled != null) main.removeCallbacks(scheduled);
            worker.execute(() -> { dictionary.close(); personal.close(); });
            worker.shutdown();
        }
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
        refresh(false);
    }

    void back() {
        Screen current = screen();
        if (current == Screen.COLLECTIONS && collection() != 0) {
            selectCollection(0);
            return;
        }
        String savedOrigin = state.get("savedOrigin");
        if (current == Screen.ENTRY && savedOrigin != null) {
            state.remove("savedOrigin");
            open(Screen.valueOf(savedOrigin));
            return;
        }
        if (current == Screen.SETTINGS) {
            open(savedScreen("settingsOrigin"));
        } else {
            open(current.parent() == current ? Screen.SEARCH : current.parent());
        }
    }

    void openSaved(String entryId,String senseId) {
        state.set("savedOrigin",screen().name());
        select(entryId,senseId);
    }

    String query() {
        String draft = state.get("query");
        return draft == null ? "" : draft;
    }

    void setQuery(String query) {
        state.set("query", query);
        refresh(true);
    }
}
