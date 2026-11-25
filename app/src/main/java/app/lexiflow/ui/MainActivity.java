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
    private OfflinePronunciation pronunciation;
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
        render();
        model.refresh(false);
    }

    private void open(Screen screen) {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
        model.open(screen);
        render();
    }

    private void render() {
        Screen screen = model.screen();
        boolean nested = screen.parent() != screen || screen == Screen.SETTINGS;
        back.setEnabled(screen != Screen.SEARCH);
        toolbar.setNavigationIcon(nested ? R.drawable.ic_back : 0);
        if (nested) toolbar.setNavigationContentDescription(R.string.back);
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
        ((TextView) findViewById(R.id.panel_title)).setText(screen == Screen.SETTINGS && !model.hasDictionary()
                ? R.string.no_installed_dictionary : screen.heading);
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
            heading.setText(model.hasDictionary() ? R.string.no_settings : R.string.no_installed_dictionary);
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
                heading.setText(model.query().trim().isEmpty() ? R.string.dictionary_prompt
                        : state.hits().isEmpty() ? R.string.dictionary_no_hits : R.string.dictionary_hits);
                body.setText(state.synthetic() ? R.string.dictionary_synthetic : R.string.pair);
                for (var hit : state.hits()) {
                    String label = hit.spelling() + (hit.pos() == null ? "" : " · " + hit.pos())
                            + " · " + getString(R.string.sense_title, hit.position());
                    addAction(rows, label, () -> {
                        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
                        query.clearFocus();
                        model.select(hit.entryId(),hit.senseId());
                        render();
                    });
                }
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
            addAction(rows,getString(R.string.save_sense,sense.position()),() -> model.save(entry,sense));
        }
        TextView credit = new TextView(this);
        credit.setText(entry.attribution());
        credit.setTextIsSelectable(true);
        rows.addView(credit);
    }

    private void renderPersonal() {
        Screen screen = model.screen();
        if (screen == Screen.SEARCH || screen == Screen.ENTRY) return;
        LinearLayout rows = findViewById(R.id.results);
        rows.removeAllViews();
        var state = model.personalView.getValue();
        if (state == null) return;
        TextView heading = findViewById(R.id.panel_title);
        TextView body = findViewById(R.id.body);
        heading.setText(screen.heading);
        body.setText(screen.body);
        switch (screen) {
            case VOCABULARY -> {
                if (!state.saved().isEmpty()) {
                    heading.setText(R.string.saved_heading);
                    body.setText(getString(R.string.saved_count,state.saved().size()));
                }
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
                if (state.saved().isEmpty() && state.collections().isEmpty() && state.reviewed() == 0) return;
                heading.setText(R.string.progress);
                body.setText(getString(R.string.stats,state.saved().size(),state.collections().size(),state.cards(),
                        state.due(),state.reviewed(),state.successful(),state.reviewed() - state.successful()));
            }
            case SETTINGS -> heading.setText(model.hasDictionary() ? R.string.no_settings : R.string.no_installed_dictionary);
            default -> { }
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
                case 0 -> { model.openSaved(item.entryId,item.senseId); render(); }
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
        inputs.setPadding(24,8,24,8);
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
                    model.changePersonal(library -> library.edit(item.id,customText,noteText));
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
        for (var item : state.saved()) {
            if (state.memberships().stream().noneMatch(m -> m.collectionId == collection.id && m.savedId == item.id)) continue;
            addAction(rows,savedLabel(item),() -> new MaterialAlertDialogBuilder(this).setTitle(item.spelling)
                    .setNegativeButton(android.R.string.cancel,null)
                    .setPositiveButton(R.string.open_saved,(d,w) -> { model.openSaved(item.entryId,item.senseId); render(); })
                    .setNeutralButton(R.string.remove_from_collection,(d,w) ->
                            model.changePersonal(library -> library.removeMember(collection.id,item.id))).show());
        }
    }

    private void addCard(PersonalDatabase.Saved item, boolean reverse) {
        var card = new PersonalDatabase.Card();
        card.savedId = item.id;
        card.reverse = reverse;
        card.due = System.currentTimeMillis();
        model.changePersonal(library -> library.addCard(card));
    }

    private void renderExercise(LinearLayout rows,TextView heading,TextView body,PersonalDatabase.Exercise exercise) {
        if (exercise == null) return;
        heading.setText(exercise.reverse ? exercise.answer : exercise.spelling);
        if (!model.answerShown()) {
            body.setText(R.string.review_prompt);
            addAction(rows,getString(R.string.reveal),() -> { model.revealAnswer(); renderPersonal(); });
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
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setMinimumHeight((int) (48 * getResources().getDisplayMetrics().density));
        button.setOnClickListener(v -> action.run());
        rows.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onStop() {
        super.onStop();
        if (pronunciation != null) pronunciation.stop();
    }

    @Override protected void onDestroy() {
        if (pronunciation != null) pronunciation.close();
        super.onDestroy();
    }
}
