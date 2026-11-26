package app.lexiflow.ui;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TranslationDisplayTest {
    @Test public void showsLiteralWordsAndLinkLabelsWhilePreservingAnnotations() {
        assertEquals("сло́во (редк.)",TranslationDisplay.wording("[[сло́во]] (редк.)"));
        assertEquals("связанный текст",TranslationDisplay.wording("[[термин|связанный текст]]"));
        assertEquals(List.of("слово","сло́во"),TranslationDisplay.alternatives(
                List.of("слово","[[слово]]","сло́во")));
    }

    @Test public void retainsUnknownSyntaxCrossReferencesAndHtmlAsLiteralText() {
        for (String source : List.of("см. термин","{{template}} [[слово]]","[[Category:term]]",
                "[[outer [[inner]]]]","[[broken","[[word|<b>label</b>]]","literal <b>text</b>")) {
            assertEquals(source,TranslationDisplay.wording(source));
        }
    }

    @Test public void projectsOnlyDictionaryAnswersInResearch() {
        String source = "[[макет]] [[проверка|проверочного]] [[блок|блока]]";
        assertEquals("макет проверочного блока",TranslationDisplay.answer(source,true,true));
        assertEquals(source,TranslationDisplay.answer(source,false,true));
        assertEquals(source,TranslationDisplay.answer(source,true,false));
        assertEquals("{{term}} [[слово]]",TranslationDisplay.answer("{{term}} [[слово]]",true,true));
    }
}
