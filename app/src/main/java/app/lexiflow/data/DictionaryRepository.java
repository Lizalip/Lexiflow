package app.lexiflow.data;

import java.util.Set;

/** Language tags of the dictionaries installed on this device. */
public interface DictionaryRepository {
    Set<String> installedLanguages();
}
