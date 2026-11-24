package app.lexiflow.data;

import java.util.Collections;
import java.util.Set;

/** The foundation ships without dictionary assets. */
public final class LocalDictionaryRepository implements DictionaryRepository {
    @Override
    public Set<String> installedLanguages() {
        return Collections.emptySet();
    }
}
