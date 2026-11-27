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
    private final java.util.List<View> pronunciationControls = new java.util.ArrayList<>();
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
        model.dictionaryView.observe(this, view -> renderDictionary());
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
        if (pronunciation != null) pronunciation.stop();
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
        findViewById(R.id.title).setVisibility(screen == Screen.ENTRY ? View.GONE : View.VISIBLE);
        findViewById(R.id.screen_panel).setVisibility(View.VISIBLE);
        findViewById(R.id.research_banner).setVisibility(screen == Screen.SEARCH
                && LocalDictionaryRepository.isResearchProfile(this) ? View.VISIBLE : View.GONE);
        setTitle(screen == Screen.ENTRY ? R.string.app_name : screen.title);
        findViewById(R.id.pair).setVisibility(screen == Screen.SEARCH || screen == Screen.SETTINGS ? View.VISIBLE : View.GONE);
        findViewById(R.id.query_box).setVisibility(screen == Screen.SEARCH ? View.VISIBLE : View.GONE);
        findViewById(R.id.saved_query_box).setVisibility(screen == Screen.VOCABULARY ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.panel_title)).setText(screen.heading);
        ((TextView) findViewById(R.id.body)).setText(screen.body);
        MaterialButton action = findViewById(R.id.action);
        action.getLayoutParams().width = screen == Screen.VOCABULARY
                ? android.view.ViewGroup.LayoutParams.MATCH_PARENT : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        action.setVisibility(screen.action == 0 ? View.GONE : View.VISIBLE);
        if (screen.action != 0) action.setText(screen.action);
        ((ScrollView) findViewById(R.id.scroll)).scrollTo(0, 0);
        renderDictionary();
        renderPersonal();
    }

    private void renderDictionary() {
        Screen screen = model.screen();
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
            if (LocalDictionaryRepository.isResearchProfile(this)) {
                addSectionHeading(rows,getString(R.string.research_details));
                addDetail(rows,state.corpusVersion() == null ? getString(R.string.research_corpus_notice)
                        : getString(R.string.research_corpus_version_notice,
                                getString(R.string.research_corpus_notice),state.corpusVersion()));
            }
            if (state.status() != DictionaryRepository.Status.LOADING
                    && !LocalDictionaryRepository.isResearchProfile(this)
                    && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                addAction(rows,getString(R.string.install_debug_v2),() -> model.installBundledV2());
            }
            return;
        }
        if (screen != Screen.SEARCH && screen != Screen.ENTRY) return;
        pronunciationControls.clear();
        rows.removeAllViews();
        findViewById(R.id.screen_panel).setVisibility(View.VISIBLE);
        heading.setText(screen.heading);
        body.setText(screen.body);
        if (screen == Screen.ENTRY && state.entry() != null) {
            findViewById(R.id.screen_panel).setVisibility(View.GONE);
            renderEntry(rows,state);
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
                    addOutlineAction(rows,getString(R.string.try_other_direction),model::toggleDirection);
                    addOutlineAction(rows,getString(R.string.clear_search),() -> query.setText(""));
                } else body.setText(dictionaryLabel);
                if (empty && !state.synthetic() && LocalDictionaryRepository.isResearchProfile(this)) {
                    var suggestions = addBlock(rows,getString(R.string.search_try_section));
                    for (String word : model.reverse() ? new String[]{"книга","дом","мир"}
                            : new String[]{"book","go","kitchen"}) {
                        addOutlineAction(suggestions,word,() -> query.setText(word));
                    }
                }
                for (var hit : state.hits()) {
                    View result = getLayoutInflater().inflate(R.layout.search_result,rows,false);
                    result.setTag(hit.senseId());
                    ((TextView)result.findViewById(R.id.result_word)).setText(hit.spelling());
                    TextView translation = result.findViewById(R.id.result_translation);
                    String preview = searchPreview(hit);
                    translation.setVisibility(preview.isEmpty() ? View.GONE : View.VISIBLE);
                    translation.setText(preview);
                    addPronunciation(result.findViewById(R.id.result_line),hit.spelling(),hit.language());
                    result.setOnClickListener(v -> {
                        rememberSearchPosition();
                        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
                        query.clearFocus();
                        model.select(hit.entryId(),hit.senseId());
                        render();
                    });
                    rows.addView(result);
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

    private String searchPreview(DictionaryRepository.Hit hit) {
        var words = new java.util.LinkedHashSet<String>();
        if (model.reverse() && hit.translation() != null) words.add(hit.translation());
        words.addAll(hit.alternatives());
        if (words.isEmpty() && hit.translation() != null) words.add(hit.translation());
        return words.stream().map(word -> TranslationDisplay.answer(word,true,
                        LocalDictionaryRepository.isResearchProfile(this))).filter(word -> !word.trim().isEmpty())
                .distinct().limit(3).collect(java.util.stream.Collectors.joining(", "));
    }

    private void renderEntry(LinearLayout rows,FoundationViewModel.DictionaryView view) {
        var entry = view.entry();
        var sense = ArticleDisplay.selectedSense(entry,model.selectedSense());
        var translations = ArticleDisplay.translations(sense,view.corpusVersion()!=null
                && view.corpusVersion().startsWith("research-"));
        View hero = getLayoutInflater().inflate(R.layout.article_hero,rows,false);
        ((TextView)hero.findViewById(R.id.article_word)).setText(entry.spelling());
        TextView translation = hero.findViewById(R.id.article_translation);
        translation.setText(translations.isEmpty() ? getString(R.string.article_no_translation) : translations.get(0));
        TextView variants = hero.findViewById(R.id.article_variants);
        variants.setVisibility(translations.size() < 2 ? View.GONE : View.VISIBLE);
        if (translations.size() > 1) variants.setText(getString(R.string.article_variants,String.join(", ",translations)));
        addPronunciation(hero.findViewById(R.id.article_word_line),entry.spelling(),entry.language());
        if (view.synthetic()) addDetail(hero.findViewById(R.id.article_hero_content),getString(R.string.dictionary_synthetic));
        if (entry.senses().size() > 1) {
            addTextAction(hero.findViewById(R.id.article_hero_content),R.string.article_other_translations,
                    () -> chooseMeaning(entry,sense,view));
        }
        rows.addView(hero);

        var metadata = new java.util.ArrayList<>(entry.metadata());
        if (sense != null) metadata.addAll(sense.metadata());
        var definitions = metadataValues(metadata,"definition");
        if (!definitions.isEmpty()) addDetail(addBlock(rows,getString(R.string.article_definition)),
                String.join("\n\n",definitions));

        var pronunciations = metadataValues(metadata,"ipa","pronunciation");
        if (!pronunciations.isEmpty()) addTranscriptions(rows,pronunciations);

        String pos = localPartOfSpeech(entry.pos());
        if (!pos.isEmpty()) addDetail(rows,getString(R.string.article_pos,pos));
        var levels = metadataValues(metadata,"cefr");
        if (!levels.isEmpty()) addDetail(rows,getString(R.string.article_level,String.join(" · ",levels)));
        if (hasArticleDetails(entry,sense,metadata)) {
            addTextAction(rows,R.string.article_more,() -> showArticleDetails(entry,sense,metadata));
        }
        var add = addAction(rows,getString(view.added() ? R.string.word_in_vocabulary : R.string.add_word),
                () -> model.addWord(view,sense));
        add.setEnabled(!view.added());
        addTextAction(rows,R.string.article_sources,() -> showArticleSources(entry));
    }

    private void chooseMeaning(DictionaryRepository.Entry entry,DictionaryRepository.Sense selected,
                               FoundationViewModel.DictionaryView view) {
        boolean research = view.corpusVersion()!=null && view.corpusVersion().startsWith("research-");
        var senses = entry.senses();
        var labels = senses.stream().map(value -> {
            String text = String.join(", ",ArticleDisplay.translations(value,research));
            if (text.isEmpty()) text = getString(R.string.article_no_translation);
            var definitions = metadataValues(value.metadata(),"definition");
            if (!definitions.isEmpty()) text += "\n" + definitions.iterator().next();
            return text;
        }).toArray(CharSequence[]::new);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.article_other_translations)
                .setSingleChoiceItems(labels,senses.indexOf(selected),(dialog,index) -> {
                    dialog.dismiss();
                    if (senses.get(index) != selected) model.select(entry.id(),senses.get(index).id());
                }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private static java.util.Set<String> metadataValues(java.util.List<DictionaryRepository.Metadata> metadata,
                                                         String... kinds) {
        var values = new java.util.LinkedHashSet<String>();
        for (var item : metadata) {
            if (java.util.Arrays.asList(kinds).contains(item.kind()) && !item.value().trim().isEmpty()) values.add(item.value());
        }
        return values;
    }

    private void addTranscriptions(LinearLayout rows,java.util.Set<String> values) {
        String full = String.join(" · ",values);
        var label = inflateArticleDetail(rows);
        label.setText(R.string.article_pronunciations);
        rows.addView(label);
        var text = inflateArticleDetail(rows);
        text.setId(R.id.article_transcriptions);
        text.setTextIsSelectable(false);
        text.setText(full);
        text.setMaxLines(2);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        rows.addView(text);
        text.post(() -> {
            var layout = text.getLayout();
            if (layout == null || layout.getLineCount() == 0
                    || layout.getEllipsisCount(layout.getLineCount()-1) == 0) return;
            text.setTextColor(getColor(R.color.blue));
            text.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(R.string.article_pronunciations)
                    .setMessage(full).setPositiveButton(android.R.string.ok,null).show());
            ViewCompat.replaceAccessibilityAction(text,androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                    .AccessibilityActionCompat.ACTION_CLICK,getString(R.string.article_show_pronunciations),null);
        });
    }

    private static boolean hasArticleDetails(DictionaryRepository.Entry entry,DictionaryRepository.Sense sense,
                                             java.util.List<DictionaryRepository.Metadata> metadata) {
        return !entry.forms().isEmpty() || (sense != null && (sense.context() != null || !sense.examples().isEmpty()))
                || !metadataValues(metadata,"usage_example","style","region","source_gloss").isEmpty();
    }

    private void showArticleDetails(DictionaryRepository.Entry entry,DictionaryRepository.Sense sense,
                                    java.util.List<DictionaryRepository.Metadata> metadata) {
        var details = new java.util.ArrayList<String>();
        if (!entry.forms().isEmpty()) {
            var forms = new java.util.LinkedHashSet<String>();
            for (var form : entry.forms()) forms.add(form.grammar() == null ? form.spelling()
                    : form.spelling()+" · "+localGrammar(form.grammar()));
            details.add(getString(R.string.word_forms)+"\n"+String.join("\n",forms));
        }
        details.addAll(metadataValues(metadata,"style","region","source_gloss"));
        if (sense != null) {
            if (sense.context() != null) details.add(sense.context().text());
            for (var example : sense.examples()) details.add(example.translation() == null ? example.body()
                    : example.body()+"\n"+example.translation());
        }
        var examples = metadataValues(metadata,"usage_example");
        if (!examples.isEmpty()) details.add(getString(R.string.article_examples)+"\n"+String.join("\n\n",examples));
        new MaterialAlertDialogBuilder(this).setTitle(R.string.article_more)
                .setMessage(String.join("\n\n",details)).setPositiveButton(android.R.string.ok,null).show();
    }

    private void showArticleSources(DictionaryRepository.Entry entry) {
        var credits = new java.util.LinkedHashSet<String>();
        credits.add(entry.attribution());
        for (var item : entry.metadata()) credits.add(item.attribution());
        for (var form : entry.forms()) credits.add(form.attribution());
        for (var sense : entry.senses()) {
            credits.add(sense.attribution());
            for (var item : sense.metadata()) credits.add(item.attribution());
            for (var example : sense.examples()) credits.add(example.attribution());
            if (sense.context() != null) credits.add(sense.context().attribution());
        }
        credits.remove(null);
        credits.remove("");
        new MaterialAlertDialogBuilder(this).setTitle(R.string.article_sources)
                .setMessage(String.join("\n\n",credits)).setPositiveButton(android.R.string.ok,null).show();
    }

    private String localPartOfSpeech(String pos) {
        if (pos == null) return "";
        return switch (pos) {
            case "n", "noun" -> getString(R.string.pos_noun);
            case "v", "verb" -> getString(R.string.pos_verb);
            case "adj", "adjective" -> getString(R.string.pos_adjective);
            case "adv", "adverb" -> getString(R.string.pos_adverb);
            case "numeral", "num" -> getString(R.string.pos_numeral);
            case "pronoun", "pron" -> getString(R.string.pos_pronoun);
            case "conjunction", "conj" -> getString(R.string.pos_conjunction);
            case "determiner", "det" -> getString(R.string.pos_determiner);
            case "interjection", "intj" -> getString(R.string.pos_interjection);
            case "particle" -> getString(R.string.pos_particle);
            case "preposition", "prep" -> getString(R.string.pos_preposition);
            case "postposition" -> getString(R.string.pos_postposition);
            case "phrase", "phraseologicalUnit" -> getString(R.string.pos_phrase);
            case "proverb" -> getString(R.string.pos_proverb);
            default -> "";
        };
    }

    private void addPronunciation(LinearLayout line,String spelling,String language) {
        if (!"en".equals(language)) return;
        if (pronunciation == null) pronunciation = new OfflinePronunciation(this,this::updatePronunciationControls);
        View icon = getLayoutInflater().inflate(R.layout.pronunciation_icon,line,false);
        icon.setContentDescription(getString(R.string.pronounce_spelling,spelling));
        icon.setOnClickListener(v -> {
            if (!pronunciation.speak(spelling)) Toast.makeText(this,R.string.pronunciation_error,Toast.LENGTH_SHORT).show();
        });
        pronunciationControls.add(icon);
        icon.setVisibility(pronunciation.available() ? View.VISIBLE : View.GONE);
        line.addView(icon);
    }

    private void updatePronunciationControls() {
        for (View control : pronunciationControls) control.setVisibility(pronunciation.available() ? View.VISIBLE : View.GONE);
    }

    private void addTextAction(LinearLayout rows,int label,Runnable action) {
        addAction(rows,R.layout.text_action_row,getString(label),action);
    }

    private TextView inflateArticleDetail(LinearLayout rows) {
        return (TextView)getLayoutInflater().inflate(R.layout.article_detail,rows,false);
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
                    var filters = addBlock(rows,getString(R.string.saved_filters));
                    addOutlineAction(filters,getString(state.notesOnly() ? R.string.show_all_saved : R.string.show_saved_notes),
                            model::toggleNotesFilter);
                    if (state.savedCount() > 1) {
                        addOutlineAction(filters,getString(state.alphabetical() ? R.string.sort_by_recent : R.string.sort_by_spelling),
                                model::toggleSavedOrder);
                    }
                } else addAction(rows,getString(R.string.study_find_word),() -> open(Screen.SEARCH));
                addPageControls(rows,state);
                for (var item : state.saved()) addOutlineAction(rows,savedLabel(item),() -> savedMenu(item,state));
            }
            case COLLECTIONS -> {
                if (model.collection() == 0) {
                    addAction(rows,getString(R.string.create_collection),() -> collectionName(null));
                    if (!state.collections().isEmpty()) {
                        heading.setText(R.string.collections);
                        body.setText(getString(R.string.collection_count,state.collections().size()));
                    }
                    for (var collection : state.collections()) {
                        addOutlineAction(rows,collection.name,() -> { model.selectCollection(collection.id); renderPersonal(); });
                    }
                } else {
                    for (var collection : state.collections()) {
                        if (collection.id == model.collection()) renderCollection(rows,heading,body,collection,state);
                    }
                }
            }
            case REVIEW -> renderLearning(rows,heading,body,state);
            case STUDY -> renderExercise(rows,heading,body,state.exercise());
            case PROGRESS -> renderProgress(rows,heading,body,state);
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

    private void renderProgress(LinearLayout rows,TextView heading,TextView body,FoundationViewModel.PersonalView state) {
        heading.setText(R.string.progress_library);
        body.setText(getString(R.string.progress_overview,state.savedCount(),state.cards(),state.reviewed()));
        var activity = addBlock(rows,getString(R.string.progress_week));
        int maximum = state.activity().stream().mapToInt(FoundationViewModel.ActivityDay::reviews).max().orElse(0);
        if (maximum == 0) {
            addDetail(activity,getString(R.string.progress_week_empty));
        } else {
            var dateFormat = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM",new java.util.Locale("ru"));
            for (var day : state.activity()) {
                addDetail(activity,getString(R.string.progress_day,dateFormat.format(day.date()),day.reviews()));
                var bar = (com.google.android.material.progressindicator.LinearProgressIndicator)getLayoutInflater()
                        .inflate(R.layout.activity_day,activity,false);
                bar.setMax(maximum);
                bar.setProgress(day.reviews());
                activity.addView(bar);
            }
        }
        var plan = addBlock(rows,getString(R.string.progress_plan));
        addDetail(plan,getString(R.string.progress_ready,state.learning().ready));
        if (state.learning().nextDue != null) addDetail(plan,nextStudyLabel(state.learning().nextDue));
        addOutlineAction(plan,getString(R.string.study_home),() -> open(Screen.REVIEW));
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
        if (!item.answer.trim().isEmpty() || !item.custom.trim().isEmpty()) {
            options = java.util.Arrays.copyOf(options,options.length+1);
            options[options.length-1] = getString(R.string.study_saved_now);
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
                case 6 -> model.learn(item);
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
        body.setText(state.rowCount() == 0 ? getString(R.string.collection_empty)
                : getString(R.string.saved_count,state.rowCount()));
        if (state.rowCount() > 0) {
            addAction(rows,getString(R.string.study_collection),() ->
                    model.startStudy(FoundationViewModel.STUDY_ALL,collection.id));
        }
        if (!state.collectionCandidates().isEmpty()) {
            addOutlineAction(rows,getString(R.string.collection_add_saved),() -> addFromSaved(collection,state.collectionCandidates()));
        } else if (state.rowCount() == 0) {
            addOutlineAction(rows,getString(R.string.study_find_word),() -> open(Screen.SEARCH));
        }
        addOutlineAction(rows,getString(R.string.rename_collection),() -> collectionName(collection));
        addOutlineAction(rows,getString(R.string.delete_collection),() -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_delete_collection)
                .setMessage(R.string.delete_collection_message)
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.remove,(dialog,which) -> model.deleteCollection(collection.id)).show());
        addPageControls(rows,state);
        for (var item : state.saved()) {
            addOutlineAction(rows,savedLabel(item),() -> new MaterialAlertDialogBuilder(this).setTitle(item.spelling)
                    .setNegativeButton(android.R.string.cancel,null)
                    .setPositiveButton(R.string.open_saved,(d,w) -> openSavedFromList(item))
                    .setNeutralButton(R.string.remove_from_collection,(d,w) ->
                            model.changePersonal(library -> library.removeMember(collection.id,item.id))).show());
        }
    }

    private void addFromSaved(PersonalDatabase.Collection collection,java.util.List<PersonalDatabase.Saved> candidates) {
        CharSequence[] labels = candidates.stream().map(this::savedLabel).toArray(CharSequence[]::new);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.collection_add_recent).setItems(labels,(dialog,index) -> {
            var member = new PersonalDatabase.Member();
            member.collectionId = collection.id;
            member.savedId = candidates.get(index).id;
            model.changePersonal(library -> library.addMember(member));
        }).setNegativeButton(android.R.string.cancel,null).show();
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
        if (learning.unstudied == 0 && learning.ready == 0) body.setText(R.string.study_up_to_date);
        if (learning.nextDue != null) addDetail(rows,nextStudyLabel(learning.nextDue));
        var plan = addBlock(rows,getString(R.string.study_choices));
        addStudyAction(plan,R.string.study_new_count,learning.unstudied,FoundationViewModel.STUDY_NEW);
        addStudyAction(plan,R.string.study_due_count,learning.ready,FoundationViewModel.STUDY_DUE);
        addOutlineAction(plan,getString(R.string.study_by_collection),() -> open(Screen.COLLECTIONS));
    }

    private String nextStudyLabel(long due) {
        String date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT).format(new java.util.Date(due));
        return getString(R.string.study_next_date,date);
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
            if (reviewed > 0) addOutlineAction(rows,getString(R.string.open_progress),() -> open(Screen.PROGRESS));
            return;
        }
        addDetail(rows,getString(R.string.study_position,model.studyPosition()+1,model.studyTotal()));
        var progress = (com.google.android.material.progressindicator.LinearProgressIndicator)getLayoutInflater()
                .inflate(R.layout.activity_day,rows,false);
        progress.setMax(model.studyTotal());
        progress.setProgress(model.studyPosition()+1);
        rows.addView(progress);
        String answer = TranslationDisplay.answer(exercise.answer,exercise.sourceAnswer,
                LocalDictionaryRepository.isResearchProfile(this));
        heading.setText(exercise.reverse ? answer : exercise.spelling);
        if (!model.answerShown(exercise)) {
            body.setText(R.string.review_prompt);
            addAction(rows,getString(R.string.reveal),() -> { model.revealAnswer(exercise); renderPersonal(); });
            return;
        }
        body.setText(exercise.reverse ? exercise.spelling : answer);
        addSectionHeading(rows,getString(R.string.study_assess));
        String[] choices = getResources().getStringArray(R.array.review_ratings);
        for (int i = 0; i < choices.length; i++) {
            int rating = i;
            var next = ReviewScheduler.basic(exercise.intervalDays,rating,0);
            String wait = next.days() == 0
                    ? getString(R.string.study_minutes,next.due()/60_000)
                    : getString(R.string.study_days,next.days());
            addOutlineAction(rows,getString(R.string.study_rating_with_interval,choices[i],wait),() -> {
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

    private void addSectionHeading(LinearLayout rows,CharSequence title) {
        TextView heading = (TextView)getLayoutInflater().inflate(R.layout.section_heading,rows,false);
        heading.setText(title);
        rows.addView(heading);
    }

    private LinearLayout addBlock(LinearLayout rows,CharSequence title) {
        View block = getLayoutInflater().inflate(R.layout.content_block,rows,false);
        rows.addView(block);
        LinearLayout content = block.findViewById(R.id.block_items);
        TextView heading = new TextView(this);
        heading.setTextAppearance(R.style.PanelHeading);
        heading.setAccessibilityHeading(true);
        heading.setText(title);
        content.addView(heading);
        return content;
    }

    private MaterialButton addAction(LinearLayout rows, CharSequence label, Runnable action) {
        return addAction(rows,R.layout.action_row,label,action);
    }

    private MaterialButton addOutlineAction(LinearLayout rows,CharSequence label,Runnable action) {
        return addAction(rows,R.layout.outline_action_row,label,action);
    }

    private MaterialButton addAction(LinearLayout rows,int layout,CharSequence label,Runnable action) {
        MaterialButton button = (MaterialButton) getLayoutInflater().inflate(layout,rows,false);
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
