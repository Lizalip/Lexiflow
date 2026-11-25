package app.lexiflow.data;

import java.util.List;
import java.util.Set;

public interface DictionaryRepository {
    enum Status { AVAILABLE, MISSING, INCOMPATIBLE, CORRUPTED, LOADING, ERROR }
    record Hit(String entryId, String senseId, String spelling, String pos, int position,
               String translation, String matchedForm) {}
    record Metadata(String kind,String value,String language,String form,String attribution) {}
    record Example(String body, String translation, String attribution) {}
    record Form(String spelling,String attribution) {}
    record SenseContext(String text, String ownerLocator, String definitionLocator, String attribution) {}
    record Sense(String id, int position, List<String> translations, List<Example> examples, String attribution,
                 List<Metadata> metadata, SenseContext context) {}
    record Entry(String id, String spelling, String pos, List<Sense> senses, String attribution,
                 List<Metadata> metadata,List<Form> forms) {}

    Status status();
    Set<String> installedLanguages();
    List<Hit> search(String query, boolean reverse);
    Entry entry(String id);
}
