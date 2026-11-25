package app.lexiflow.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import app.lexiflow.R;
import app.lexiflow.data.DictionaryRepository;
import app.lexiflow.data.PersonalDatabase;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

public final class MainActivity extends AppCompatActivity {
    private FoundationViewModel model;
    private MaterialToolbar toolbar;
    private BottomNavigationView navigation;
    private TextInputEditText query;
    private TextInputEditText savedQuery;
    private OfflinePronunciation pronunciation;
    private Integer restoredPersonalScroll;
    private final OnBackPressedCallback back = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {
            model.back();
            render();
        }
    };

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        model = new ViewModelProvider(this).get(FoundationViewModel.class);
        model.connect(this);
        if (savedState != null && savedState.containsKey("personalScroll")) {
            restoredPersonalScroll = savedState.getInt("personalScroll");
        }
        toolbar = findViewById(R.id.toolbar);
        navigation = findViewById(R.id.navigation);
        query = findViewById(R.id.query);
        query.setText(model.query());
        query.setSelection(query.length());
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                model.setQuery(s.toString());
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        savedQuery = findViewById(R.id.saved_query);
        savedQuery.setText(model.savedQuery());
        savedQuery.setSelection(savedQuery.length());
        savedQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                model.setSavedQuery(s.toString());
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        navigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            open(id == R.id.search ? Screen.SEARCH : id == R.id.review ? Screen.REVIEW : Screen.VOCABULARY);
            return true;
        });
        toolbar.setOnMenuItemClickListener(item -> {
            open(Screen.SETTINGS);
            return true;
        });
        toolbar.setNavigationOnClickListener(v -> back.handleOnBackPressed());
        findViewById(R.id.action).setOnClickListener(v -> open(model.screen().detail()));
        getOnBackPressedDispatcher().addCallback(this, back);
        findViewById(R.id.direction).setOnClickListener(v -> model.toggleDirection());
        model.dictionaryView.observe(this, view -> renderDictionary());
        model.personalView.observe(this, view -> renderPersonal());
        model.personalError.observe(this, error -> {
            if (Boolean.TRUE.equals(error)) Toast.makeText(this,R.string.personal_error,Toast.LENGTH_SHORT).show();
        });
        model.notice.observe(this, message -> {
            if (message == null) return;
            Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
            model.notice.setValue(null);
        });
        render();
        model.refresh(false);
    }

    private void open(Screen screen) {
        rememberSearchPosition();
        if (screen != Screen.SETTINGS) restoredPersonalScroll = null;
        var input = savedQuery.hasFocus() ? savedQuery : query;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        query.clearFocus();
        savedQuery.clearFocus();
        model.open(screen);
        render();
    }

    private void render() {
        Screen screen = model.screen();
        boolean nested = screen.parent() != screen || screen == Screen.SETTINGS;
        back.setEnabled(screen != Screen.SEARCH);
        if (nested) {
            toolbar.setNavigationIcon(R.drawable.ic_back);
            toolbar.setNavigationContentDescription(R.string.back);
        } else {
            toolbar.setNavigationIcon(null);
        }
        toolbar.getMenu().findItem(R.id.settings).setVisible(screen != Screen.SETTINGS);
        navigation.setVisibility(nested ? View.GONE : View.VISIBLE);
        if (!nested) {
            int id = screen == Screen.SEARCH ? R.id.search : screen == Screen.REVIEW ? R.id.review : R.id.vocabulary;
            navigation.getMenu().findItem(id).setChecked(true);
        }
        ((TextView) findViewById(R.id.title)).setText(screen.title);
        setTitle(screen.title);
        findViewById(R.id.pair).setVisibility(screen == Screen.SEARCH || screen == Screen.SETTINGS ? View.VISIBLE : View.GONE);
        findViewById(R.id.query_box).setVisibility(screen == Screen.SEARCH ? View.VISIBLE : View.GONE);
        findViewById(R.id.saved_query_box).setVisibility(screen == Screen.VOCABULARY ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.panel_title)).setText(screen.heading);
        ((TextView) findViewById(R.id.body)).setText(screen.body);
        MaterialButton action = findViewById(R.id.action);
        action.setVisibility(screen.action == 0 ? View.GONE : View.VISIBLE);
        if (screen.action != 0) action.setText(screen.action);
        ((ScrollView) findViewById(R.id.scroll)).scrollTo(0, 0);
        renderDictionary();
        renderPersonal();
    }

    private void renderDictionary() {
        Screen screen = model.screen();
        if (screen != Screen.ENTRY && pronunciation != null) pronunciation.stop();
        TextView heading = findViewById(R.id.panel_title);
        TextView body = findViewById(R.id.body);
        MaterialButton direction = findViewById(R.id.direction);
        LinearLayout rows = findViewById(R.id.results);
        direction.setVisibility(screen == Screen.SEARCH ? View.VISIBLE : View.GONE);
        direction.setText(model.reverse() ? R.string.reverse_search : R.string.forward_search);
        var state = model.dictionaryView.getValue();
        if (state == null) return;
        if (screen == Screen.SETTINGS) {
            rows.removeAllViews();
            heading.setText(switch (state.status()) {
                case AVAILABLE -> R.string.dictionary_available;
                case MISSING -> R.string.no_installed_dictionary;
                case LOADING -> R.string.dictionary_loading;
                case ERROR -> R.string.dictionary_error;
                case CORRUPTED -> R.string.dictionary_corrupted;
                case INCOMPATIBLE -> R.string.dictionary_incompatible;
            });
            if (state.status() != DictionaryRepository.Status.LOADING
                    && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                addAction(rows,getString(R.string.install_debug_v2),() -> model.installBundledV2());
            }
            return;
        }
        if (screen != Screen.SEARCH && screen != Screen.ENTRY) return;
        rows.removeAllViews();
        heading.setText(screen.heading);
        body.setText(screen.body);
        if (screen == Screen.ENTRY && state.entry() != null) {
            renderEntry(rows, heading, body, state.entry());
            return;
        }
        if (screen == Screen.SEARCH) {
            findViewById(R.id.action).setVisibility(state.status() == DictionaryRepository.Status.MISSING
                    ? View.VISIBLE : View.GONE);
        }
        switch (state.status()) {
            case LOADING -> { heading.setText(R.string.dictionary_loading); body.setText(""); }
            case MISSING -> { }
            case INCOMPATIBLE -> { heading.setText(R.string.dictionary_incompatible); body.setText(""); }
            case CORRUPTED -> { heading.setText(R.string.dictionary_corrupted); body.setText(""); }
            case ERROR -> { heading.setText(R.string.dictionary_error); body.setText(""); }
            case AVAILABLE -> {
                if (screen == Screen.ENTRY) {
                    heading.setText(R.string.entry_unavailable);
                    body.setText("");
                    return;
                }
                boolean empty = model.query().trim().isEmpty();
                heading.setText(empty ? R.string.dictionary_prompt
                        : state.hits().isEmpty() ? R.string.dictionary_no_hits : R.string.dictionary_hits);
                int dictionaryLabel = state.synthetic() ? R.string.dictionary_synthetic : R.string.pair;
                if (!empty && state.hits().isEmpty()) {
                    body.setText(getString(R.string.dictionary_no_hits_help,getString(dictionaryLabel)));
                } else body.setText(dictionaryLabel);
                for (var hit : state.hits()) {
                    String label = hit.spelling() + (hit.pos() == null ? "" : " · " + hit.pos())
                            + " · " + getString(R.string.sense_title, hit.position());
                    if (hit.translation() != null) label += "\n" + hit.translation();
                    if (hit.matchedForm() != null) label += "\n" + getString(R.string.matched_form,hit.matchedForm());
                    addAction(rows, label, () -> {
                        rememberSearchPosition();
                        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
                        query.clearFocus();
                        model.select(hit.entryId(),hit.senseId());
                        render();
                    });
                }
                ScrollView scroll = findViewById(R.id.scroll);
                scroll.post(() -> {
                    if (model.screen() == Screen.SEARCH && model.dictionaryView.getValue() == state) {
                        scroll.scrollTo(0, model.searchScroll());
                    }
                });
            }
        }
    }

    private void renderEntry(LinearLayout rows, TextView heading, TextView body, DictionaryRepository.Entry entry) {
        heading.setText(entry.spelling());
        body.setText(entry.pos());
        if (model.dictionaryView.getValue().synthetic()) {
            if (entry.pos() == null) body.setText(R.string.dictionary_synthetic);
            else body.setText(getString(R.string.dictionary_synthetic_pos,entry.pos()));
        }
        if (pronunciation == null) pronunciation = new OfflinePronunciation(this,this::renderDictionary);
        if (pronunciation.available()) {
            addAction(rows,getString(R.string.pronounce_offline),() -> {
                if (!pronunciation.speak(entry.spelling())) {
                    Toast.makeText(this,R.string.pronunciation_error,Toast.LENGTH_SHORT).show();
                }
            });
        }
        addAction(rows,getString(R.string.save_entry),() -> model.save(entry,null));
        addMetadata(rows,entry.metadata());
        if (!entry.forms().isEmpty()) {
            TextView forms = (TextView)getLayoutInflater().inflate(R.layout.entry_forms,rows,false);
            var text = new StringBuilder(getString(R.string.word_forms));
            for (var form : entry.forms()) text.append("\n").append(form.spelling()).append("\n").append(form.attribution());
            forms.setText(text);
            rows.addView(forms);
        }
        for (var sense : entry.senses()) {
            TextView details = new TextView(this);
            details.setTextIsSelectable(true);
            StringBuilder text = new StringBuilder(getString(R.string.sense_title, sense.position()));
            if (sense.id().equals(model.selectedSense())) text.append(" · ").append(getString(R.string.selected_sense));
            for (String word : sense.translations()) text.append("\n").append(word);
            for (var example : sense.examples()) {
                text.append("\n\n").append(example.body());
                if (example.translation() != null) text.append("\n").append(example.translation());
                text.append("\n").append(example.attribution());
            }
            text.append("\n\n").append(sense.attribution()).append("\n");
            details.setText(text);
            rows.addView(details);
            addMetadata(rows,sense.metadata());
            if (sense.context() != null) {
                TextView context = new TextView(this);
                context.setText(getString(R.string.metadata_line,getString(R.string.source_context),
                        sense.context().text(),sense.context().attribution()));
                context.setTextIsSelectable(true);
                rows.addView(context);
            }
            addAction(rows,getString(R.string.save_sense,sense.position()),() -> model.save(entry,sense));
        }
        TextView credit = new TextView(this);
        credit.setText(entry.attribution());
        credit.setTextIsSelectable(true);
        rows.addView(credit);
    }

    private void addMetadata(LinearLayout rows,java.util.List<DictionaryRepository.Metadata> metadata) {
        for (var value : metadata) {
            int label = switch (value.kind()) {
                case "ipa" -> R.string.metadata_ipa;
                case "pronunciation" -> R.string.metadata_pronunciation;
                case "region" -> R.string.metadata_region;
                case "style" -> R.string.metadata_style;
                case "definition" -> R.string.metadata_definition;
                case "source_gloss" -> R.string.metadata_gloss;
                case "cefr" -> R.string.metadata_cefr;
                case "grammar" -> R.string.metadata_grammar;
                default -> 0;
            };
            if (label == 0) continue;
            TextView detail = new TextView(this);
            String text = value.form() == null ? value.value() : value.form() + " · " + value.value();
            detail.setText(getString(R.string.metadata_line,getString(label),text,value.attribution()));
            detail.setTextIsSelectable(true);
            rows.addView(detail);
        }
    }

    private void renderPersonal() {
        Screen screen = model.screen();
        if (screen == Screen.SEARCH || screen == Screen.ENTRY || screen == Screen.SETTINGS) return;
        LinearLayout rows = findViewById(R.id.results);
        rows.removeAllViews();
        var state = model.personalView.getValue();
        if (state == null || state.screen() != screen || state.collection() != model.collection()) return;
        TextView heading = findViewById(R.id.panel_title);
        TextView body = findViewById(R.id.body);
        heading.setText(screen.heading);
        body.setText(screen.body);
        switch (screen) {
            case VOCABULARY -> {
                if (state.savedCount() > 0) {
                    heading.setText(R.string.saved_heading);
                    if (state.rowCount() == 0 && !state.savedQuery().trim().isEmpty()) body.setText(R.string.no_saved_matches);
                    else if (state.notesOnly() && state.rowCount() == 0) body.setText(R.string.no_saved_notes);
                    else body.setText(getString(R.string.saved_count,state.rowCount()));
                    addAction(rows,getString(state.notesOnly() ? R.string.show_all_saved : R.string.show_saved_notes),
                            model::toggleNotesFilter);
                    if (state.savedCount() > 1) {
                        addAction(rows,getString(state.alphabetical() ? R.string.sort_by_recent : R.string.sort_by_spelling),
                                model::toggleSavedOrder);
                    }
                }
                addPageControls(rows,state);
                for (var item : state.saved()) addAction(rows,savedLabel(item),() -> savedMenu(item,state));
            }
            case COLLECTIONS -> {
                if (model.collection() == 0) {
                    addAction(rows,getString(R.string.create_collection),() -> collectionName(null));
                    if (!state.collections().isEmpty()) {
                        heading.setText(R.string.collections);
                        body.setText(getString(R.string.collection_count,state.collections().size()));
                    }
                    for (var collection : state.collections()) {
                        addAction(rows,collection.name,() -> { model.selectCollection(collection.id); renderPersonal(); });
                    }
                } else {
                    for (var collection : state.collections()) {
                        if (collection.id == model.collection()) renderCollection(rows,heading,body,collection,state);
                    }
                }
            }
            case REVIEW -> renderExercise(rows,heading,body,state.exercise());
            case PROGRESS -> {
                if (state.savedCount() == 0 && state.collections().isEmpty() && state.reviewed() == 0) return;
                heading.setText(R.string.progress);
                body.setText(getString(R.string.stats,state.savedCount(),state.collections().size(),state.cards(),
                        state.due(),state.reviewed(),state.successful(),state.reviewed() - state.successful()));
            }
            default -> { }
        }
        if (restoredPersonalScroll != null && (screen == Screen.VOCABULARY || screen == Screen.COLLECTIONS)) {
            ScrollView scroll = findViewById(R.id.scroll);
            int position = restoredPersonalScroll;
            restoredPersonalScroll = null;
            scroll.post(() -> {
                if (model.screen() == screen) scroll.scrollTo(0,position);
            });
        }
    }

    private String savedLabel(PersonalDatabase.Saved item) {
        String label = item.spelling + " · " + (item.custom.isEmpty() ? item.answer : item.custom);
        return item.note.isEmpty() ? label : label + "\n" + item.note;
    }

    private void savedMenu(PersonalDatabase.Saved item, FoundationViewModel.PersonalView state) {
        String[] options = getResources().getStringArray(R.array.saved_actions);
        new MaterialAlertDialogBuilder(this).setTitle(item.spelling).setItems(options,(dialog,choice) -> {
            switch (choice) {
                case 0 -> openSavedFromList(item);
                case 1 -> editSaved(item);
                case 2 -> chooseCollection(item,state);
                case 3 -> addCard(item,false);
                case 4 -> addCard(item,true);
                case 5 -> new MaterialAlertDialogBuilder(this).setTitle(R.string.remove_saved)
                        .setMessage(R.string.remove_saved_message).setNegativeButton(android.R.string.cancel,null)
                        .setPositiveButton(R.string.remove,(confirm,ignored) -> model.changePersonal(library -> library.remove(item.id))).show();
                default -> { }
            }
        }).show();
    }

    private void editSaved(PersonalDatabase.Saved item) {
        LinearLayout inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.VERTICAL);
        int horizontal = getResources().getDimensionPixelSize(R.dimen.space_large);
        int vertical = getResources().getDimensionPixelSize(R.dimen.space_small);
        inputs.setPaddingRelative(horizontal,vertical,horizontal,vertical);
        EditText translation = new EditText(this);
        translation.setHint(R.string.custom_translation);
        translation.setText(item.custom);
        EditText note = new EditText(this);
        note.setHint(R.string.note);
        note.setText(item.note);
        inputs.addView(translation);
        inputs.addView(note);
        new MaterialAlertDialogBuilder(this).setTitle(item.spelling).setView(inputs)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.save,(dialog,which) -> {
                    String customText = translation.getText().toString().trim();
                    String noteText = note.getText().toString().trim();
                    model.edit(item.id,customText,noteText);
                }).show();
    }

    private void chooseCollection(PersonalDatabase.Saved item, FoundationViewModel.PersonalView state) {
        if (state.collections().isEmpty()) {
            Toast.makeText(this,R.string.create_collection_first,Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] names = state.collections().stream().map(value -> value.name).toArray(CharSequence[]::new);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.collections).setItems(names,(dialog,which) -> {
            var member = new PersonalDatabase.Member();
            member.collectionId = state.collections().get(which).id;
            member.savedId = item.id;
            model.changePersonal(library -> library.addMember(member));
        }).show();
    }

    private void collectionName(PersonalDatabase.Collection existing) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.collection_name);
        if (existing != null) name.setText(existing.name);
        var dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.create_collection : R.string.rename_collection).setView(name)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.save,null).create();
        dialog.setOnShowListener(v -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { name.setError(getString(R.string.collection_name)); return; }
            var state = model.personalView.getValue();
            if (state != null && state.collections().stream().anyMatch(row -> row.name.equals(value)
                    && (existing == null || row.id != existing.id))) {
                name.setError(getString(R.string.collection_duplicate));
                return;
            }
            model.changePersonal(library -> {
                if (existing == null) {
                    var collection = new PersonalDatabase.Collection();
                    collection.name = value;
                    library.create(collection);
                } else {
                    library.rename(existing.id,value);
                }
            });
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void renderCollection(LinearLayout rows,TextView heading,TextView body,
                                  PersonalDatabase.Collection collection,FoundationViewModel.PersonalView state) {
        heading.setText(collection.name);
        body.setText(R.string.collection_items);
        addAction(rows,getString(R.string.rename_collection),() -> collectionName(collection));
        addAction(rows,getString(R.string.delete_collection),() -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_delete_collection)
                .setMessage(R.string.delete_collection_message)
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.remove,(dialog,which) -> model.deleteCollection(collection.id)).show());
        addPageControls(rows,state);
        for (var item : state.saved()) {
            addAction(rows,savedLabel(item),() -> new MaterialAlertDialogBuilder(this).setTitle(item.spelling)
                    .setNegativeButton(android.R.string.cancel,null)
                    .setPositiveButton(R.string.open_saved,(d,w) -> openSavedFromList(item))
                    .setNeutralButton(R.string.remove_from_collection,(d,w) ->
                            model.changePersonal(library -> library.removeMember(collection.id,item.id))).show());
        }
    }

    private void addPageControls(LinearLayout rows,FoundationViewModel.PersonalView state) {
        int size = FoundationViewModel.PERSONAL_PAGE_SIZE;
        if (state.rowCount() <= size) return;
        TextView position = new TextView(this);
        position.setText(getString(R.string.personal_page_range,state.page()*size+1,
                Math.min((state.page()+1)*size,state.rowCount()),state.rowCount()));
        rows.addView(position);
        if (state.page() > 0) addAction(rows,getString(R.string.personal_previous_page),
                () -> openPersonalPage(state.page()-1));
        if ((state.page()+1)*size < state.rowCount()) addAction(rows,getString(R.string.personal_next_page),
                () -> openPersonalPage(state.page()+1));
    }

    private void openSavedFromList(PersonalDatabase.Saved item) {
        restoredPersonalScroll = findViewById(R.id.scroll).getScrollY();
        model.openSaved(item.entryId,item.senseId);
        render();
    }

    private void openPersonalPage(int page) {
        model.changePersonalPage(page);
        ((ScrollView) findViewById(R.id.scroll)).scrollTo(0,0);
    }

    private void addCard(PersonalDatabase.Saved item, boolean reverse) {
        var card = new PersonalDatabase.Card();
        card.savedId = item.id;
        card.reverse = reverse;
        card.due = System.currentTimeMillis();
        model.changePersonal(library -> library.addCard(card));
    }

    private void renderExercise(LinearLayout rows,TextView heading,TextView body,PersonalDatabase.Exercise exercise) {
        if (exercise == null) {
            int reviewed = model.sessionReviewed();
            if (reviewed > 0) {
                heading.setText(R.string.review_session_finished);
                body.setText(getString(R.string.review_session_summary,reviewed,model.sessionSuccessful(),
                        reviewed-model.sessionSuccessful()));
            }
            return;
        }
        heading.setText(exercise.reverse ? exercise.answer : exercise.spelling);
        if (!model.answerShown(exercise)) {
            body.setText(R.string.review_prompt);
            addAction(rows,getString(R.string.reveal),() -> { model.revealAnswer(exercise); renderPersonal(); });
            return;
        }
        body.setText(exercise.reverse ? exercise.spelling : exercise.answer);
        String[] choices = getResources().getStringArray(R.array.review_ratings);
        for (int i = 0; i < choices.length; i++) {
            int rating = i;
            addAction(rows,choices[i],() -> { rows.removeAllViews(); model.grade(exercise,rating); });
        }
    }

    private void addAction(LinearLayout rows, CharSequence label, Runnable action) {
        MaterialButton button = (MaterialButton) getLayoutInflater().inflate(R.layout.action_row,rows,false);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        rows.addView(button);
    }

    private void rememberSearchPosition() {
        if (model.screen() == Screen.SEARCH
                && model.dictionaryView.getValue() != null
                && model.dictionaryView.getValue().status() == DictionaryRepository.Status.AVAILABLE) {
            model.setSearchScroll(findViewById(R.id.scroll).getScrollY());
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        rememberSearchPosition();
        if (model.screen() == Screen.VOCABULARY || model.screen() == Screen.COLLECTIONS) {
            outState.putInt("personalScroll",findViewById(R.id.scroll).getScrollY());
        } else if (restoredPersonalScroll != null) {
            outState.putInt("personalScroll",restoredPersonalScroll);
        }
        super.onSaveInstanceState(outState);
    }

    @Override protected void onStop() {
        super.onStop();
        if (pronunciation != null) pronunciation.stop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (model.screen() == Screen.REVIEW || model.screen() == Screen.PROGRESS) model.refreshPersonal();
    }

    @Override protected void onDestroy() {
        if (pronunciation != null) pronunciation.close();
        super.onDestroy();
    }
}
