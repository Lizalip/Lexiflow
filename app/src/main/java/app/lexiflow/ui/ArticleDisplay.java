package app.lexiflow.ui;

import app.lexiflow.data.DictionaryRepository.Entry;
import app.lexiflow.data.DictionaryRepository.Sense;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class ArticleDisplay {
    private ArticleDisplay() {}

    static Sense selectedSense(Entry entry,String id) {
        for (var sense : entry.senses()) if (sense.id().equals(id)) return sense;
        for (var sense : entry.senses()) if (!translations(sense,false).isEmpty()) return sense;
        return entry.senses().isEmpty() ? null : entry.senses().get(0);
    }

    static List<String> translations(Sense sense,boolean research) {
        var values = new LinkedHashSet<String>();
        if (sense != null) for (String value : sense.translations()) {
            String display = research ? TranslationDisplay.wording(value).trim() : value.trim();
            if (!display.isEmpty()) values.add(display);
        }
        return new ArrayList<>(values);
    }
}
