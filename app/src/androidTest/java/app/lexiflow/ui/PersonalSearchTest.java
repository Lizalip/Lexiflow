package app.lexiflow.ui;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.data.PersonalDatabase;
import app.lexiflow.data.ReviewScheduler;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PersonalSearchTest {
    @Test public void learningReusesSavedSenseAndSeparatesNewCardsFromScheduledReviews() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var personal = Room.inMemoryDatabaseBuilder(context,PersonalDatabase.class).build();
        try {
            var library = personal.library();
            long now = 10_000;
            var item = new PersonalDatabase.Saved();
            item.entryId = "fixture-entry";
            item.senseId = "fixture-sense";
            item.answer = "Исходный перевод";
            long saved = library.save(item);
            library.edit(saved,"Личный перевод","Заметка");

            long card = library.learn(item,now);
            assertTrue(card > 0);
            assertEquals(card,library.learn(item,now+1));
            assertEquals(1,library.savedCount());
            assertEquals("Заметка",library.saved().get(0).note);
            var exercise = library.learningExercise(card,now);
            assertEquals("Личный перевод",exercise.answer);
            assertEquals(List.of(card),library.learningQueue(now,FoundationViewModel.STUDY_NEW,0,20));
            assertTrue(library.learningQueue(now,FoundationViewModel.STUDY_DUE,0,20).isEmpty());

            assertTrue(library.grade(exercise,0,now,ReviewScheduler::basic));
            long next = now + 600_000;
            assertEquals(card,library.learn(item,now+1));
            assertNull(library.learningExercise(card,next-1));
            assertEquals(Long.valueOf(next),library.learningCounts(next-1).nextDue);
            assertEquals(0,library.learningCounts(next).unstudied);
            assertEquals(1,library.learningCounts(next).ready);
            assertEquals(List.of(card),library.learningQueue(next,FoundationViewModel.STUDY_DUE,0,20));
        } finally {
            personal.close();
        }
    }

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
