package app.lexiflow.ui;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.data.PersonalDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class PersonalSearchTest {
    @Test public void filtersLiteralTextAndPagesWithStableSortWithoutDictionary() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var personal = Room.inMemoryDatabaseBuilder(context,PersonalDatabase.class).build();
        try {
            var library = personal.library();
            long alpha = save(library,"Alpha","Исходный ответ","");
            long zeta = save(library,"zeta","","Заметка [*]?");
            library.edit(alpha,"Личный перевод","");
            assertEquals(zeta,library.savedPage(1,0).get(0).id);
            assertEquals(alpha,library.savedPage(1,1).get(0).id);
            var note = FoundationViewModel.savedPattern("зАМЕТКА [*]?");
            assertEquals(1,library.filteredSavedCount(false,note));
            assertEquals(zeta,library.filteredSavedPage(true,note,true,1,0).get(0).id);
            assertEquals(0,library.filteredSavedCount(true,FoundationViewModel.savedPattern("alpha")));
            assertEquals(alpha,library.filteredSavedPage(false,FoundationViewModel.savedPattern("ALPHA"),false,1,0).get(0).id);
            assertEquals(alpha,library.filteredSavedPage(false,FoundationViewModel.savedPattern("исходный"),false,1,0).get(0).id);
            assertEquals(alpha,library.filteredSavedPage(false,FoundationViewModel.savedPattern("личный"),false,1,0).get(0).id);
            assertEquals(alpha,library.filteredSavedPage(false,null,true,1,0).get(0).id);
            assertEquals(zeta,library.filteredSavedPage(false,null,true,1,1).get(0).id);
            library.remove(zeta);
            assertEquals(0,library.filteredSavedPage(false,null,true,1,1).size());
        } finally {
            personal.close();
        }
    }

    private static long save(PersonalDatabase.Library library,String spelling,String answer,String note) {
        var item = new PersonalDatabase.Saved();
        item.entryId = spelling;
        item.senseId = spelling;
        item.spelling = spelling;
        item.answer = answer;
        item.note = note;
        return library.save(item);
    }
}
