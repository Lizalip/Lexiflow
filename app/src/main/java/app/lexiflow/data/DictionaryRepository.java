package app.lexiflow.data;

import java.util.List;
import java.util.Set;

public interface DictionaryRepository {
    enum Status { AVAILABLE, MISSING, INCOMPATIBLE, CORRUPTED, LOADING, ERROR }
    record Hit(String entryId, String senseId, String spelling, String pos, int position,
               String translation, String matchedForm, String language, boolean accentMatch, List<String> alternatives) {
        public Hit(String entryId,String senseId,String spelling,String pos,int position,String translation,String matchedForm) {
            this(entryId,senseId,spelling,pos,position,translation,matchedForm,"en",false,java.util.Collections.emptyList());
        }
    }
    record Metadata(String kind,String value,String language,String form,String attribution) {}
    record Example(String body, String translation, String attribution) {}
    record Form(String spelling,String attribution,String grammar) {}
    record SenseContext(String text, String ownerLocator, String definitionLocator, String attribution) {}
    record Sense(String id, int position, List<String> translations, List<Example> examples, String attribution,
                 List<Metadata> metadata, SenseContext context) {}
    record Entry(String id, String spelling, String pos, List<Sense> senses, String attribution,
                 List<Metadata> metadata,List<Form> forms,String language) {
        public Entry(String id,String spelling,String pos,List<Sense> senses,String attribution,
                     List<Metadata> metadata,List<Form> forms) {
            this(id,spelling,pos,senses,attribution,metadata,forms,"en");
        }
    }

    Status status();
    Set<String> installedLanguages();
    List<Hit> search(String query, boolean reverse);
    Entry entry(String id);
}
