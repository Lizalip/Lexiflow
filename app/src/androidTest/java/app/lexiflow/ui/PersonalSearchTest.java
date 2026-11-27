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
    @Test public void quizSchedulesOneEventAcrossLegacyDirectionsAndCanBeRetried() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var personal = Room.inMemoryDatabaseBuilder(context,PersonalDatabase.class).build();
        try {
            var library = personal.library();
            long saved = save(library,"test-word","тест","");
            long now = 1_000;
            for (boolean reverse : new boolean[]{false,true}) {
                var card = new PersonalDatabase.Card();
                card.savedId = saved;
                card.reverse = reverse;
                card.due = now;
                library.addCard(card);
            }
            assertEquals(1,library.learningCounts(now).unstudied);
            var queue = library.quizQueue(now,FoundationViewModel.STUDY_NEW,0,10);
            assertEquals(1,queue.size());
            var exercise = queue.get(0);
            var grade = new PersonalDatabase.Library.QuizGrade(exercise.id,exercise.savedId,exercise.due,exercise.intervalDays,2);
            library.gradeQuiz(List.of(grade),now);
            library.gradeQuiz(List.of(grade),now);
            assertEquals(1,library.reviewCount());
            assertEquals(2,library.cardCount());
            assertEquals(0,library.dueCount(now));
            long again = now+2*86_400_000L;
            assertEquals(1,library.quizQueue(again,FoundationViewModel.STUDY_DUE,0,10).size());
        } finally {
            personal.close();
        }
    }

    @Test public void voluntaryPracticeKeepsScheduleAndReviewHistory() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var personal = Room.inMemoryDatabaseBuilder(context,PersonalDatabase.class).build();
        try {
            var library = personal.library();
            long now = 10_000;
            long saved = save(library,"book","книга","");
            long outside = save(library,"house","дом","");
            var collection = new PersonalDatabase.Collection();
            collection.name = "Study";
            long collectionId = library.create(collection);
            var member = new PersonalDatabase.Member();
            member.collectionId = collectionId;
            member.savedId = saved;
            library.addMember(member);
            assertEquals(0,library.cardCount());
            var initial = library.quizQueue(now,FoundationViewModel.STUDY_ALL,collectionId,10);
            assertEquals(1,initial.size());
            var word = initial.get(0);
            assertEquals(saved,word.savedId);
            assertEquals(1,library.cardCount());
            library.gradeQuiz(List.of(grade(word,2)),now,now);

            long early = now+1;
            var repeat = library.quizQueue(early,FoundationViewModel.STUDY_ALL,collectionId,10);
            assertEquals(1,repeat.size());
            var scheduled = repeat.get(0);
            assertEquals(word.id,scheduled.id);
            assertTrue(scheduled.due>early);
            assertEquals(2,scheduled.intervalDays);
            var counts = library.learningCounts(early);
            assertEquals(1,counts.studied);
            assertEquals(0,counts.ready);
            assertEquals(1,counts.unstudied);
            var review = library.quizQueue(early,FoundationViewModel.STUDY_DUE,0,10);
            assertEquals(1,review.size());
            assertEquals(saved,review.get(0).savedId);
            assertEquals(outside,library.quizQueue(early,FoundationViewModel.STUDY_NEW,0,10).get(0).savedId);

            // Completion just after the scheduled time must still count as voluntary.
            library.gradeQuiz(List.of(grade(scheduled,0)),scheduled.due+1,early);
            var preserved = library.quizQueue(early,FoundationViewModel.STUDY_ALL,collectionId,10).get(0);
            assertEquals(scheduled.id,preserved.id);
            assertEquals(scheduled.intervalDays,preserved.intervalDays);
            assertEquals(scheduled.due,preserved.due);
            assertEquals(2,library.cardCount());
            assertEquals(1,library.reviewCount());
            assertEquals(1,library.successCount());
            assertEquals(1,library.learningCounts(scheduled.due).ready);
        } finally {
            personal.close();
        }
    }

    private static PersonalDatabase.Library.QuizGrade grade(PersonalDatabase.Exercise exercise,int rating) {
        return new PersonalDatabase.Library.QuizGrade(exercise.id,exercise.savedId,exercise.due,exercise.intervalDays,rating);
    }

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
