package app.lexiflow.ui;

import app.lexiflow.R;

enum Screen {
    SEARCH(R.string.dictionary, R.string.no_corpus, R.string.no_corpus_body, R.string.open_entry),
    VOCABULARY(R.string.vocabulary, R.string.no_words, R.string.no_words_body, R.string.open_collections),
    REVIEW(R.string.review, R.string.no_review, R.string.no_review_body, R.string.open_progress),
    ENTRY(R.string.entry, R.string.no_entry, R.string.no_entry_body, 0),
    COLLECTIONS(R.string.collections, R.string.no_collections, R.string.no_collections_body, 0),
    PROGRESS(R.string.progress, R.string.no_progress, R.string.no_progress_body, 0),
    SETTINGS(R.string.settings, R.string.no_settings, R.string.settings_body, 0);

    final int title;
    final int heading;
    final int body;
    final int action;

    Screen(int title, int heading, int body, int action) {
        this.title = title;
        this.heading = heading;
        this.body = body;
        this.action = action;
    }

    Screen detail() {
        return switch (this) {
            case SEARCH -> ENTRY;
            case VOCABULARY -> COLLECTIONS;
            case REVIEW -> PROGRESS;
            default -> this;
        };
    }

    Screen parent() {
        return switch (this) {
            case ENTRY -> SEARCH;
            case COLLECTIONS -> VOCABULARY;
            case PROGRESS -> REVIEW;
            default -> this;
        };
    }
}
