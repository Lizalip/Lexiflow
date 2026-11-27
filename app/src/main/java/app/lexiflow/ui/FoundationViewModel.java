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
import app.lexiflow.R;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class FoundationViewModel extends ViewModel {
    private final SavedStateHandle state;
    record DictionaryView(DictionaryRepository.Status status, boolean synthetic,
                          List<DictionaryRepository.Hit> hits, DictionaryRepository.Entry entry, String corpusVersion,
                          boolean added) {
        DictionaryView(DictionaryRepository.Status status,boolean synthetic,List<DictionaryRepository.Hit> hits,
                       DictionaryRepository.Entry entry,String corpusVersion) {
            this(status,synthetic,hits,entry,corpusVersion,false);
        }
    }
    private LocalDictionaryRepository dictionary;
    private PersonalDatabase personal;
    private android.content.SharedPreferences preferences;
    private Handler main;
    private ExecutorService worker;
    private Runnable scheduled;
    private Runnable scheduledPersonalSearch;
    private volatile int request;
    private volatile boolean disposed;
    final MutableLiveData<DictionaryView> dictionaryView = new MutableLiveData<>();
    final MutableLiveData<LocalDictionaryRepository.ActivationStage> activation = new MutableLiveData<>();
    static final int PERSONAL_PAGE_SIZE = 50;
    static final int STUDY_ALL = 0, STUDY_NEW = 1, STUDY_DUE = 2;
    record PersonalView(Screen screen,long collection,int page,int rowCount,int savedCount,
                        boolean notesOnly,String savedQuery,boolean alphabetical,
                        List<PersonalDatabase.Saved> saved, Map<String,String> sourceLanguages, List<PersonalDatabase.Collection> collections,
                        int cards,int due,int reviewed,int successful,
                        PersonalDatabase.LearningCounts learning,List<ActivityDay> activity,
                        List<PersonalDatabase.Saved> collectionCandidates) {}
    record ActivityDay(LocalDate date,int reviews) {}
    final MutableLiveData<PersonalView> personalView = new MutableLiveData<>();
    final MutableLiveData<Boolean> personalError = new MutableLiveData<>();
    final MutableLiveData<Integer> notice = new MutableLiveData<>();
    final MutableLiveData<Boolean> studyNavigation = new MutableLiveData<>();
    final MutableLiveData<Boolean> quizUpdated = new MutableLiveData<>();
    private boolean grading;
    private boolean preparingStudy;

    public FoundationViewModel(SavedStateHandle state) {
        this.state = state;
    }

    boolean hasDictionary() {
        return dictionary != null && dictionary.status() == DictionaryRepository.Status.AVAILABLE;
    }

    void connect(Context context) {
        if (worker != null) return;
        dictionary = LocalDictionaryRepository.observingActivation(context,stage -> main.post(() -> {
            if (disposed) return;
            if (stage == LocalDictionaryRepository.ActivationStage.PREVIOUS_RETAINED) {
                notice.setValue(R.string.dictionary_previous_retained);
            } else {
                activation.setValue(stage);
            }
        }));
        personal = Room.databaseBuilder(context.getApplicationContext(),PersonalDatabase.class,"lexiflow-personal.sqlite").build();
        preferences = context.getApplicationContext().getSharedPreferences("lexiflow-learning",Context.MODE_PRIVATE);
        if (!state.contains("quizLimit")) state.set("quizLimit",preferences.getInt("quizLimit",20));
        main = new Handler(Looper.getMainLooper());
        worker = Executors.newSingleThreadExecutor();
    }

    void refresh(boolean debounce) {
        if (worker == null) return;
        if (scheduled != null) main.removeCallbacks(scheduled);
        final int current = ++request;
        final String text = query();
        final String entryId = state.get("entryId");
        final String senseId = selectedSense();
        final boolean reverse = reverse();
        final Screen screen = screen();
        if (screen == Screen.STUDY) {
            finishQuiz();
            return;
        }
        if (screen != Screen.SEARCH && screen != Screen.ENTRY && screen != Screen.SETTINGS) {
            refreshPersonal();
            return;
        }
        dictionaryView.setValue(new DictionaryView(DictionaryRepository.Status.LOADING,false,Collections.emptyList(),null,null));
        scheduled = () -> worker.execute(() -> {
            if (current != request) return;
            DictionaryView result;
            try {
                dictionary.load();
                List<DictionaryRepository.Hit> hits = screen == Screen.SEARCH ? dictionary.search(text,reverse) : Collections.emptyList();
                DictionaryRepository.Entry entry = screen == Screen.ENTRY && entryId != null ? dictionary.entry(entryId) : null;
                boolean added = entry != null && isAdded(personal.library(),entry,ArticleDisplay.selectedSense(entry,senseId));
                result = new DictionaryView(dictionary.status(),dictionary.isSynthetic(),hits,entry,dictionary.corpusVersion(),added);
            } catch (RuntimeException failure) {
                result = new DictionaryView(DictionaryRepository.Status.ERROR,false,Collections.emptyList(),null,null);
            }
            DictionaryView completed = result;
            main.post(() -> { if (current == request) dictionaryView.setValue(completed); });
        });
        main.postDelayed(scheduled, debounce ? 250 : 0);
    }

    boolean reverse() { return Boolean.TRUE.equals(state.get("reverse")); }
    void toggleDirection() { state.set("reverse", !reverse()); setSearchScroll(0); refresh(false); }
    int searchScroll() { Integer scroll = state.get("searchScroll"); return scroll == null ? 0 : scroll; }
    void setSearchScroll(int scroll) { state.set("searchScroll",scroll); }
    String selectedSense() { return state.get("senseId"); }
    void select(String entryId, String senseId) {
        state.set("entryId",entryId);
        state.set("senseId",senseId);
        open(Screen.ENTRY);
    }

    void refreshPersonal() { changePersonal(library -> {}); }

    void changePersonal(Consumer<PersonalDatabase.Library> change) {
        if (worker == null) return;
        Screen target = screen();
        long collection = collection();
        int requestedPage = personalPage(target);
        boolean notesOnly = target == Screen.VOCABULARY && notesOnly();
        boolean alphabetical = target == Screen.VOCABULARY && alphabetical();
        String savedQuery = target == Screen.VOCABULARY ? savedQuery() : "";
        worker.execute(() -> {
            try {
                PersonalDatabase.Library library = personal.library();
                change.accept(library);
                long now = System.currentTimeMillis();
                int savedCount = target == Screen.VOCABULARY || target == Screen.PROGRESS || target == Screen.REVIEW
                        ? library.savedCount() : 0;
                String pattern = savedPattern(savedQuery);
                boolean filtered = notesOnly || pattern != null;
                int rowCount = target == Screen.VOCABULARY
                        ? (filtered ? library.filteredSavedCount(notesOnly,pattern) : savedCount)
                        : target == Screen.COLLECTIONS && collection != 0 ? library.collectionSize(collection) : 0;
                int page = Math.min(requestedPage, Math.max(0,(rowCount-1)/PERSONAL_PAGE_SIZE));
                int offset = page * PERSONAL_PAGE_SIZE;
                List<PersonalDatabase.Saved> saved = target == Screen.VOCABULARY
                        ? (filtered || alphabetical
                                ? library.filteredSavedPage(notesOnly,pattern,alphabetical,PERSONAL_PAGE_SIZE,offset)
                                : library.savedPage(PERSONAL_PAGE_SIZE,offset))
                        : target == Screen.COLLECTIONS && collection != 0
                        ? library.collectionPage(collection,PERSONAL_PAGE_SIZE,offset) : Collections.emptyList();
                var entryIds = saved.stream().map(row -> row.entryId).collect(java.util.stream.Collectors.toSet());
                var snapshot = new PersonalView(target,collection,page,rowCount,savedCount,notesOnly,savedQuery,alphabetical,saved,
                        dictionary.sourceLanguages(entryIds),
                        target == Screen.VOCABULARY || target == Screen.COLLECTIONS || target == Screen.PROGRESS || target == Screen.REVIEW
                                ? library.collections() : Collections.emptyList(),
                        target == Screen.PROGRESS ? library.cardCount() : 0,
                        target == Screen.PROGRESS ? library.dueCount(now) : 0,
                        target == Screen.PROGRESS ? library.reviewCount() : 0,
                        target == Screen.PROGRESS ? library.successCount() : 0,
                        target == Screen.REVIEW || target == Screen.PROGRESS ? library.learningCounts(now) : null,
                        target == Screen.PROGRESS ? activity(library,now) : Collections.emptyList(),
                        target == Screen.COLLECTIONS && collection != 0
                                ? library.recentOutsideCollection(collection,PERSONAL_PAGE_SIZE) : Collections.emptyList());
                main.post(() -> {
                    if (disposed || target != screen() || collection != collection()
                            || requestedPage != personalPage(target)
                            || (target == Screen.VOCABULARY
                            && (notesOnly != notesOnly() || alphabetical != alphabetical()
                            || !savedQuery.equals(savedQuery())))) return;
                    state.set(pageKey(target),page);
                    personalError.setValue(false);
                    personalView.setValue(snapshot);
                });
            } catch (RuntimeException failure) {
                main.post(() -> { if (!disposed) personalError.setValue(true); });
            }
        });
    }

    private static List<ActivityDay> activity(PersonalDatabase.Library library,long now) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate last = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate first = last.minusDays(6);
        long end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        var times = library.reviewTimes(first.atStartOfDay(zone).toInstant().toEpochMilli(),end);
        return activityDays(first,zone,times);
    }

    static List<ActivityDay> activityDays(LocalDate first,ZoneId zone,List<Long> timestamps) {
        var counts = new int[7];
        for (long timestamp : timestamps) {
            long index = java.time.temporal.ChronoUnit.DAYS.between(first,Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate());
            if (index >= 0 && index < counts.length) counts[(int)index]++;
        }
        var days = new java.util.ArrayList<ActivityDay>();
        for (int i = 0; i < counts.length; i++) days.add(new ActivityDay(first.plusDays(i),counts[i]));
        return days;
    }

    private static boolean isAdded(PersonalDatabase.Library library,DictionaryRepository.Entry entry,
                                   DictionaryRepository.Sense sense) {
        long id = library.savedId(entry.id(),sense == null ? "" : sense.id());
        return id > 0 && (ArticleDisplay.translations(sense,false).isEmpty() || library.hasStudyCard(id));
    }

    void addWord(DictionaryView view,DictionaryRepository.Sense sense) {
        if (worker == null || view.added() || view != dictionaryView.getValue() || screen() != Screen.ENTRY) return;
        var entry = view.entry();
        var item = savedItem(entry,sense);
        boolean hasAnswer = !ArticleDisplay.translations(sense,false).isEmpty();
        worker.execute(() -> {
            try {
                var library = personal.library();
                if (!isAdded(library,entry,sense)) {
                    if (hasAnswer) library.learn(item,System.currentTimeMillis());
                    else library.save(item);
                }
                main.post(() -> {
                    if (disposed || screen() != Screen.ENTRY || view != dictionaryView.getValue()) return;
                    dictionaryView.setValue(new DictionaryView(view.status(),view.synthetic(),view.hits(),
                            entry,view.corpusVersion(),true));
                    notice.setValue(hasAnswer ? R.string.word_added_for_study : R.string.saved_notice);
                });
            } catch (RuntimeException failure) {
                notifyResult(R.string.personal_error);
            }
        });
    }

    void learn(PersonalDatabase.Saved item) {
        if (item.answer.trim().isEmpty() && item.custom.trim().isEmpty()) return;
        prepareQuiz(library -> {
            long now = System.currentTimeMillis();
            long id = library.learn(item,now);
            var exercise = library.learningExercise(id,now);
            return exercise == null ? Collections.emptyList() : Collections.singletonList(exercise);
        });
    }

    void startStudy(int mode,long collection) {
        prepareQuiz(library -> library.quizQueue(System.currentTimeMillis(),mode,collection,40));
    }

    private void prepareQuiz(java.util.function.Function<PersonalDatabase.Library,List<PersonalDatabase.Exercise>> lookup) {
        if (worker == null || preparingStudy || grading) return;
        if (canResumeStudy() || (quiz()!=null && !quiz().saved())) { navigateStudy(Screen.STUDY); return; }
        Screen origin = screen();
        int originRequest = request;
        long originCollection = collection();
        int limit = studyLimit();
        preparingStudy = true;
        worker.execute(() -> {
            try {
                var library = personal.library();
                dictionary.load();
                var quiz = QuizFactory.create(dictionary,lookup.apply(library),library.savedPage(40,0),
                        limit,new java.util.Random());
                main.post(() -> {
                    preparingStudy = false;
                    if (disposed || origin != screen() || request != originRequest || originCollection != collection()) return;
                    if (quiz.finished()) {
                        notice.setValue(R.string.quiz_none_available);
                        return;
                    }
                    state.set("quiz",quiz);
                    navigateStudy(Screen.STUDY);
                });
            } catch (RuntimeException failure) {
                main.post(() -> {
                    preparingStudy = false;
                    if (!disposed) personalError.setValue(true);
                });
            }
        });
    }

    private void navigateStudy(Screen target) {
        open(target);
        studyNavigation.setValue(true);
    }

    QuizSession quiz() { return state.get("quiz"); }
    boolean canResumeStudy() { var quiz = quiz(); return quiz != null && !quiz.finished(); }
    boolean grading() { return grading; }
    int studyLimit() { Integer value = state.get("quizLimit"); return value == null ? 20 : value; }
    void setStudyLimit(int value) {
        if (value != 10 && value != 20 && value != 30) throw new IllegalArgumentException("invalid quiz limit");
        state.set("quizLimit",value);
        if (preferences!=null) preferences.edit().putInt("quizLimit",value).apply();
        refreshPersonal();
    }

    void answerQuiz(int choice) {
        var quiz = quiz();
        if (quiz != null && quiz.answer(choice,new java.util.Random())) state.set("quiz",quiz);
    }

    void advanceQuiz() {
        var quiz = quiz();
        if (quiz == null) return;
        if (quiz.advance()) state.set("quiz",quiz);
        finishQuiz();
    }

    private void finishQuiz() {
        var quiz = quiz();
        if (worker == null || quiz == null || !quiz.finished() || quiz.saved() || grading) return;
        var grades = quiz.grades();
        grading = true;
        worker.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                personal.library().gradeQuiz(grades,now);
                Long next = personal.library().learningCounts(now).nextDue;
                main.post(() -> {
                    grading = false;
                    if (disposed || quiz != quiz()) return;
                    quiz.markSaved(next);
                    state.set("quiz",quiz);
                    quizUpdated.setValue(true);
                });
            } catch (RuntimeException failure) {
                main.post(() -> {
                    grading = false;
                    if (!disposed) {
                        personalError.setValue(true);
                        quizUpdated.setValue(true);
                    }
                });
            }
        });
    }

    private PersonalDatabase.Saved savedItem(DictionaryRepository.Entry entry,DictionaryRepository.Sense sense) {
        var item = new PersonalDatabase.Saved();
        item.entryId = entry.id();
        item.senseId = sense == null ? "" : sense.id();
        item.spelling = entry.spelling();
        item.answer = sense == null ? String.join(" · ",entry.senses().stream()
                .flatMap(value -> value.translations().stream()).distinct().collect(java.util.stream.Collectors.toList()))
                : String.join(" · ",sense.translations());
        return item;
    }

    void edit(long id,String translation,String note) {
        changePersonal(library -> {
            notifyResult(library.edit(id,translation,note) == 1 ? R.string.edited_notice : R.string.personal_error);
        });
    }

    void installBundledV2() {
        ++request;
        if (scheduled != null) main.removeCallbacks(scheduled);
        dictionaryView.setValue(new DictionaryView(DictionaryRepository.Status.LOADING,false,Collections.emptyList(),null,null));
        worker.execute(() -> {
            try {
                dictionary.installBundledV2();
                main.post(() -> {
                    if (!disposed) {
                        notice.setValue(R.string.dictionary_updated);
                        refresh(false);
                    }
                });
            } catch (Exception failure) {
                notifyResult(R.string.dictionary_update_rejected);
                main.post(() -> { if (!disposed) refresh(false); });
            }
        });
    }

    private void notifyResult(int message) {
        main.post(() -> { if (!disposed) notice.setValue(message); });
    }

    long collection() {
        Long id = state.get("collection");
        return id == null ? 0 : id;
    }
    void selectCollection(long id) {
        state.set("collection",id);
        state.set(pageKey(Screen.COLLECTIONS),0);
        refreshPersonal();
    }

    private static String pageKey(Screen screen) { return "personalPage." + screen.name(); }

    private int personalPage(Screen screen) {
        Integer page = state.get(pageKey(screen));
        return page == null ? 0 : page;
    }

    void changePersonalPage(int page) {
        state.set(pageKey(screen()),page);
        refreshPersonal();
    }

    private boolean notesOnly() { return Boolean.TRUE.equals(state.get("notesOnly")); }
    private boolean alphabetical() { return Boolean.TRUE.equals(state.get("savedAlphabetical")); }

    void toggleSavedOrder() {
        state.set("savedAlphabetical",!alphabetical());
        changePersonalPage(0);
    }

    String savedQuery() {
        String value = state.get("savedQuery");
        return value == null ? "" : value;
    }

    void setSavedQuery(String value) {
        if (value.equals(savedQuery())) return;
        state.set("savedQuery",value);
        state.set(pageKey(Screen.VOCABULARY),0);
        if (main == null) return;
        if (scheduledPersonalSearch != null) main.removeCallbacks(scheduledPersonalSearch);
        scheduledPersonalSearch = () -> {
            if (screen() == Screen.VOCABULARY) refreshPersonal();
        };
        main.postDelayed(scheduledPersonalSearch,250);
    }

    static String savedPattern(String query) {
        String cleaned = query.trim();
        if (cleaned.isEmpty()) return null;
        var pattern = new StringBuilder("*");
        cleaned.codePoints().forEach(code -> {
            int lower = Character.toLowerCase(code);
            int upper = Character.toUpperCase(code);
            if (lower != upper) {
                pattern.append('[').appendCodePoint(lower).appendCodePoint(upper).append(']');
            } else {
                switch (code) {
                    case '*' -> pattern.append("[*]");
                    case '?' -> pattern.append("[?]");
                    case '[' -> pattern.append("[[]");
                    default -> pattern.appendCodePoint(code);
                }
            }
        });
        return pattern.append('*').toString();
    }

    void toggleNotesFilter() {
        state.set("notesOnly",!notesOnly());
        changePersonalPage(0);
    }

    void deleteCollection(long id) {
        changePersonal(library -> {
            library.deleteCollection(id);
            main.post(() -> {
                if (!disposed && screen() == Screen.COLLECTIONS && collection() == id) selectCollection(0);
            });
        });
    }


    @Override protected void onCleared() {
        if (worker != null) {
            disposed = true;
            ++request;
            if (scheduled != null) main.removeCallbacks(scheduled);
            if (scheduledPersonalSearch != null) main.removeCallbacks(scheduledPersonalSearch);
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
        if (query.equals(query())) return;
        setSearchScroll(0);
        state.set("query", query);
        refresh(true);
    }
}
