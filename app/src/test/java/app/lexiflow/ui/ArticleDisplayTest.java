package app.lexiflow.ui;

import app.lexiflow.data.DictionaryRepository;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArticleDisplayTest {
    @Test public void preservesForeignAccentsAndPersonalTextWhileDeduplicatingRussianMarks() {
        assertEquals("лэптоп · ноутбук",TranslationDisplay.answer("лэпто́п · но́утбук · ноутбу́к",true,true));
        assertEquals("ёлка, мой, café, café",TranslationDisplay.russian("ё́лка, мо́й, café, café"));
        assertEquals("ноутбу́к",TranslationDisplay.answer("ноутбу́к",false,true));
    }
    private static DictionaryRepository.Sense sense(String id,List<String> words) {
        return new DictionaryRepository.Sense(id,1,words,List.of(),"",List.of(),null);
    }

    @Test public void alternativesKeepSourceOrderAndCollapseEquivalentDisplayText() {
        var selected = sense("selected",List.of("раз","[[один]]","один","","одно"));
        assertEquals(List.of("раз","один","одно"),ArticleDisplay.translations(selected,true));
        assertEquals(List.of("один"),ArticleDisplay.translations(sense("single",List.of("один")),true));
    }

    @Test public void selectionKeepsSeparateMeaningsEvenWhenOneHasNoTranslation() {
        var withoutAnswer = sense("missing",List.of());
        var withAnswer = sense("available",List.of("перевод"));
        var entry = new DictionaryRepository.Entry("entry","word","n",List.of(withoutAnswer,withAnswer),"",List.of(),List.of());
        assertSame(withoutAnswer,ArticleDisplay.selectedSense(entry,"missing"));
        assertSame(withAnswer,ArticleDisplay.selectedSense(entry,null));
        assertTrue(ArticleDisplay.translations(withoutAnswer,false).isEmpty());
    }
}
