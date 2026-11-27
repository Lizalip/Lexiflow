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
import app.lexiflow.data.LocalDictionaryRepository;
import app.lexiflow.data.PersonalDatabase;
import app.lexiflow.data.ReviewScheduler;
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
    private MaterialButton pronunciationButton;
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
        TextView researchBanner = findViewById(R.id.research_banner);
        boolean researchProfile = LocalDictionaryRepository.isResearchProfile(this);
        if (researchProfile) researchBanner.setVisibility(View.VISIBLE);
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
            open(id == R.id.search ? Screen.SEARCH : id == R.id.review ? Screen.REVIEW
                    : id == R.id.progress ? Screen.PROGRESS : Screen.VOCABULARY);
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
        model.dictionaryView.observe(this, view -> {
            if (researchProfile && view.corpusVersion() != null) {
                researchBanner.setText(getString(R.string.research_corpus_version_notice,
                        getString(R.string.research_corpus_notice),view.corpusVersion()));
            }
            renderDictionary();
        });
        model.activation.observe(this,stage -> {
            var view = model.dictionaryView.getValue();
            if (view != null && view.status() == DictionaryRepository.Status.LOADING) renderDictionary();
        });
        model.personalView.observe(this, view -> renderPersonal());
        model.studyNavigation.observe(this, changed -> {
            if (Boolean.TRUE.equals(changed)) {
                model.studyNavigation.setValue(false);
                render();
            }
        });
        model.personalError.observe(this, error -> {
            if (Boolean.TRUE.equals(error)) {
                Toast.makeText(this,R.string.personal_error,Toast.LENGTH_SHORT).show();
                model.personalError.setValue(false);
            }
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
        if (screen != Screen.SETTINGS) {
            restoredPersonalScroll = null;
        } else if (model.screen() == Screen.VOCABULARY || model.screen() == Screen.COLLECTIONS) {
            restoredPersonalScroll = findViewById(R.id.scroll).getScrollY();
        }
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
            int id = screen == Screen.SEARCH ? R.id.search : screen == Screen.REVIEW ? R.id.review
                    : screen == Screen.PROGRESS ? R.id.progress : R.id.vocabulary;
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
                case LOADING -> dictionaryLoadingLabel();
                case ERROR -> R.string.dictionary_error;
                case CORRUPTED -> R.string.dictionary_corrupted;
                case INCOMPATIBLE -> R.string.dictionary_incompatible;
            });
            if (state.status() != DictionaryRepository.Status.LOADING
                    && !LocalDictionaryRepository.isResearchProfile(this)
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
            case LOADING -> { heading.setText(dictionaryLoadingLabel()); body.setText(""); }
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
                    if (hit.translation() != null) label += "\n" + (state.corpusVersion()!=null
                            && state.corpusVersion().startsWith("research-")
                            ? TranslationDisplay.wording(hit.translation()) : hit.translation());
                    if ("ru".equals(hit.language())) label += "\n" + getString(R.string.direct_russian_article);
                    if (hit.accentMatch()) label += "\n" + getString(R.string.accent_search_match);
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

    private int dictionaryLoadingLabel() {
        var stage = model.activation.getValue();
        if (stage == null) return R.string.dictionary_loading;
        return switch (stage) {
            case PREPARING -> R.string.dictionary_preparing;
            case VERIFYING -> R.string.dictionary_verifying;
            case ACTIVATING -> R.string.dictionary_activating;
            default -> R.string.dictionary_loading;
        };
    }

    private void renderEntry(LinearLayout rows, TextView heading, TextView body, DictionaryRepository.Entry entry) {
        pronunciationButton = null;
        heading.setText(entry.spelling());
        body.setText(entry.pos());
        if (model.dictionaryView.getValue().synthetic()) {
            if (entry.pos() == null) body.setText(R.string.dictionary_synthetic);
            else body.setText(getString(R.string.dictionary_synthetic_pos,entry.pos()));
        }
        if ("en".equals(entry.language()) && pronunciation == null) {
            pronunciation = new OfflinePronunciation(this,this::updatePronunciationButton);
        }
        if ("en".equals(entry.language())) {
            pronunciationButton = addAction(rows,getString(R.string.pronounce_offline),() -> {
                if (!pronunciation.speak(entry.spelling())) {
                    Toast.makeText(this,R.string.pronunciation_error,Toast.LENGTH_SHORT).show();
                }
            });
            updatePronunciationButton();
        }
        if ("ru".equals(entry.language())) {
            if (pronunciation != null) pronunciation.stop();
            body.setText(getString(R.string.direct_russian_part_of_speech,entry.pos()));
        }
        addAction(rows,getString(R.string.save_entry),() -> model.save(entry,null));
        boolean research = LocalDictionaryRepository.isResearchProfile(this);
        if (!research) addMetadata(rows,entry.metadata(),false);
        else addMetadata(rows,entry.metadata().stream().filter(MainActivity::primaryArticleMetadata)
                .collect(java.util.stream.Collectors.toList()),false);
        if (!entry.forms().isEmpty()) {
            TextView forms = (TextView)getLayoutInflater().inflate(R.layout.entry_forms,rows,false);
            var text = new StringBuilder(getString(R.string.word_forms));
            for (var form : entry.forms()) {
                text.append("\n").append(form.spelling());
                if (form.grammar()!=null) text.append(" · ").append(localGrammar(form.grammar()));
                text.append("\n").append(form.attribution());
            }
            forms.setText(text);
            rows.addView(forms);
        }
        for (var sense : entry.senses()) {
            TextView details = inflateArticleDetail(rows);
            StringBuilder text = new StringBuilder(getString(R.string.sense_title, sense.position()));
            if (sense.id().equals(model.selectedSense())) text.append(" · ").append(getString(R.string.selected_sense));
            for (String word : research ? TranslationDisplay.alternatives(sense.translations()) : sense.translations()) {
                text.append("\n").append(word);
            }
            for (var example : sense.examples()) {
                text.append("\n\n").append(example.body());
                if (example.translation() != null) text.append("\n").append(example.translation());
                text.append("\n").append(example.attribution());
            }
            text.append("\n\n").append(sense.attribution()).append("\n");
            details.setText(text);
            rows.addView(details);
            addMetadata(rows,sense.metadata(),false);
            if (sense.context() != null) {
                TextView context = inflateArticleDetail(rows);
                context.setText(getString(R.string.metadata_line,getString(R.string.source_context),
                        sense.context().text(),sense.context().attribution()));
                rows.addView(context);
            }
            if (sense.translations().stream().anyMatch(word -> !word.trim().isEmpty())) {
                addAction(rows,getString(R.string.study_add_sense,sense.position()),() -> model.learn(entry,sense));
            }
            addAction(rows,getString(R.string.save_sense,sense.position()),() -> model.save(entry,sense));
        }
        if (research) addMetadata(rows,entry.metadata().stream().filter(value -> !primaryArticleMetadata(value))
                .collect(java.util.stream.Collectors.toList()),true);
        TextView credit = inflateArticleDetail(rows);
        credit.setText(entry.attribution());
        rows.addView(credit);
    }

    private void updatePronunciationButton() {
        if (pronunciationButton != null) {
            pronunciationButton.setVisibility(pronunciation.available() ? View.VISIBLE : View.GONE);
        }
    }

    private TextView inflateArticleDetail(LinearLayout rows) {
        return (TextView)getLayoutInflater().inflate(R.layout.article_detail,rows,false);
    }

    private static boolean primaryArticleMetadata(DictionaryRepository.Metadata value) {
        return "ipa".equals(value.kind()) || "pronunciation".equals(value.kind()) || "cefr".equals(value.kind());
    }

    private String localGrammar(String value) {
        return switch (value) {
            case "Past" -> getString(R.string.grammar_past);
            case "Past participle" -> getString(R.string.grammar_past_participle);
            case "Present participle" -> getString(R.string.grammar_present_participle);
            case "Gerund" -> getString(R.string.grammar_gerund);
            case "Third-person singular" -> getString(R.string.grammar_third_person_singular);
            case "Plural" -> getString(R.string.grammar_plural);
            case "Comparative" -> getString(R.string.grammar_comparative);
            case "Superlative" -> getString(R.string.grammar_superlative);
            default -> value;
        };
    }

    private void addMetadata(LinearLayout rows,java.util.List<DictionaryRepository.Metadata> metadata,
                             boolean sourceEntryNotes) {
        for (var value : metadata) {
            int label = switch (value.kind()) {
                case "ipa" -> R.string.metadata_ipa;
                case "pronunciation" -> R.string.metadata_pronunciation;
                case "region" -> R.string.metadata_region;
                case "style" -> R.string.metadata_style;
                case "definition" -> sourceEntryNotes ? R.string.research_definition_note : R.string.metadata_definition;
                case "source_gloss" -> R.string.metadata_gloss;
                case "cefr" -> R.string.metadata_cefr;
                case "usage_example" -> R.string.source_usage_example;
                case "grammar" -> R.string.metadata_grammar;
                default -> 0;
            };
            if (label == 0) continue;
            TextView detail = inflateArticleDetail(rows);
            String text = value.form() == null ? value.value() : value.form() + " · " + value.value();
            detail.setText(getString(R.string.metadata_line,getString(label),text,value.attribution()));
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
            case REVIEW -> renderLearning(rows,heading,body,state);
            case STUDY -> renderExercise(rows,heading,body,state.exercise());
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
        boolean sourceAnswer = item.custom.isEmpty();
        String text = sourceAnswer ? item.answer : item.custom;
        String label = item.spelling + " · " + TranslationDisplay.answer(text,sourceAnswer,
                LocalDictionaryRepository.isResearchProfile(this));
        return item.note.isEmpty() ? label : label + "\n" + item.note;
    }

    private void savedMenu(PersonalDatabase.Saved item, FoundationViewModel.PersonalView state) {
        String[] options = getResources().getStringArray(R.array.saved_actions);
        String language = state.sourceLanguages().get(item.entryId);
        if ("ru".equals(language)) {
            String original = options[3];
            options[3] = options[4];
            options[4] = original;
        } else if (language == null) {
            options[3] = getString(R.string.card_word_to_translation);
            options[4] = getString(R.string.card_translation_to_word);
        }
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
        View inputs = getLayoutInflater().inflate(R.layout.dialog_saved_edit,null);
        EditText translation = inputs.findViewById(R.id.edit_saved_translation);
        translation.setText(item.custom);
        EditText note = inputs.findViewById(R.id.edit_saved_note);
        note.setText(item.note);
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
        if (state.rowCount() > 0) {
            addAction(rows,getString(R.string.study_collection),() ->
                    model.startStudy(FoundationViewModel.STUDY_ALL,collection.id));
        }
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

    private void renderLearning(LinearLayout rows,TextView heading,TextView body,FoundationViewModel.PersonalView state) {
        var learning = state.learning();
        if (model.canResumeStudy()) {
            addAction(rows,getString(R.string.study_resume),() -> open(Screen.STUDY));
        }
        if (learning.cards == 0) {
            addAction(rows,getString(state.savedCount() == 0 ? R.string.study_find_word : R.string.study_open_saved),
                    () -> open(state.savedCount() == 0 ? Screen.SEARCH : Screen.VOCABULARY));
            body.setText(state.savedCount() == 0 ? R.string.study_first_from_dictionary : R.string.study_first_from_saved);
            return;
        }
        heading.setText(R.string.study_today);
        body.setText(R.string.study_today_body);
        addStudyAction(rows,R.string.study_new_count,learning.unstudied,FoundationViewModel.STUDY_NEW);
        addStudyAction(rows,R.string.study_due_count,learning.ready,FoundationViewModel.STUDY_DUE);
        if (learning.nextDue != null) {
            String date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                    java.text.DateFormat.SHORT).format(new java.util.Date(learning.nextDue));
            addDetail(rows,getString(R.string.study_next_date,date));
        }
        addAction(rows,getString(R.string.open_collections),() -> open(Screen.COLLECTIONS));
    }

    private void addStudyAction(LinearLayout rows,int label,int count,int mode) {
        addAction(rows,getString(label,count),() -> model.startStudy(mode,0)).setEnabled(count > 0);
    }

    private void renderExercise(LinearLayout rows,TextView heading,TextView body,PersonalDatabase.Exercise exercise) {
        if (exercise == null) {
            int reviewed = model.sessionReviewed();
            if (reviewed > 0) {
                heading.setText(R.string.review_session_finished);
                body.setText(getString(R.string.review_session_summary,reviewed,model.sessionSuccessful(),
                        reviewed-model.sessionSuccessful()));
            } else {
                heading.setText(R.string.no_review);
                body.setText(R.string.study_none_available);
            }
            addAction(rows,getString(R.string.study_home),() -> open(Screen.REVIEW));
            return;
        }
        addDetail(rows,getString(R.string.study_position,model.studyPosition()+1,model.studyTotal()));
        String answer = TranslationDisplay.answer(exercise.answer,exercise.sourceAnswer,
                LocalDictionaryRepository.isResearchProfile(this));
        heading.setText(exercise.reverse ? answer : exercise.spelling);
        if (!model.answerShown(exercise)) {
            body.setText(R.string.review_prompt);
            addAction(rows,getString(R.string.reveal),() -> { model.revealAnswer(exercise); renderPersonal(); });
            return;
        }
        body.setText(exercise.reverse ? exercise.spelling : answer);
        String[] choices = getResources().getStringArray(R.array.review_ratings);
        for (int i = 0; i < choices.length; i++) {
            int rating = i;
            var next = ReviewScheduler.basic(exercise.intervalDays,rating,0);
            String wait = next.days() == 0
                    ? getString(R.string.study_minutes,next.due()/60_000)
                    : getString(R.string.study_days,next.days());
            addAction(rows,getString(R.string.study_rating_with_interval,choices[i],wait),() -> {
                model.grade(exercise,rating);
                renderPersonal();
            }).setEnabled(!model.grading());
        }
    }

    private void addDetail(LinearLayout rows,CharSequence text) {
        TextView detail = inflateArticleDetail(rows);
        detail.setText(text);
        rows.addView(detail);
    }

    private MaterialButton addAction(LinearLayout rows, CharSequence label, Runnable action) {
        MaterialButton button = (MaterialButton) getLayoutInflater().inflate(R.layout.action_row,rows,false);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        rows.addView(button);
        return button;
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
        if (model.screen() == Screen.REVIEW || model.screen() == Screen.PROGRESS
                || model.screen() == Screen.STUDY) model.refreshPersonal();
    }

    @Override protected void onDestroy() {
        if (pronunciation != null) pronunciation.close();
        super.onDestroy();
    }
}
