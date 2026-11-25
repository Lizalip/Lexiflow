package app.lexiflow.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class DictionarySearchTest {
    @Test public void usesExactIndexedKeysForAPlainRussianWord() {
        var query = DictionarySearch.russianStressFallback("ёлка");
        assertNotNull(query);
        assertArrayEquals(new String[]{"ё\u0301лка","ёлка\u0301"},query.arguments());
        assertTrue(query.sql().contains("search_key IN (?,?)"));
    }

    @Test public void excludesPhrasesPunctuationLongQueriesAndAlreadyAccentedKeys() {
        for (String disallowed : new String[]{"x","кн","по-русски","от руки","книга!",
                "понима\u0301ть","а".repeat(33)}) {
            assertNull(DictionarySearch.russianStressFallback(disallowed));
        }
    }
}
