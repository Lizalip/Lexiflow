package app.lexiflow.ui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class QuizSessionTest {
    private final Random random = new Random(7);

    @Test public void generatesOnlyDistinctUnambiguousOptions() {
        var word = word(1);
        var question = QuizFactory.options(word,false,"book","книга",List.of("кни́га","книжка"),
                List.of("книга","кни́жка","Дом","дом","улица","стол"),random);
        assertNotNull(question);
        assertEquals(4,question.options().size());
        assertFalse(question.options().contains("кни́жка"));
        assertTrue(question.options().contains("книга"));
        assertNull(QuizFactory.options(word,false,"book","книга",List.of("книга"),
                List.of("кни́га"),random));
    }

    @Test public void repeatsAfterOtherQuestionsAndLocksTheFirstTap() {
        var first = question(1,false);
        var opposite = question(1,true);
        var other = question(2,false);
        var session = new QuizSession(10,List.of(first,other,opposite));
        assertTrue(session.answer(1,random));
        assertFalse(session.answer(0,random));
        assertEquals(4,session.total());
        session.advance();
        assertEquals(other,session.question());
        while (!session.finished()) {
            session.answer(session.question().options().indexOf(session.question().correct()),random);
            session.advance();
        }
        assertEquals(4,session.answered());
        assertEquals(3,session.correct());
        assertEquals(2,session.uniqueWords());
        assertEquals(1,session.grades().get(0).rating());
    }

    @Test public void ratesDirectionCoverageAndBoundsSessionsWithRepeatedErrors() {
        assertEquals(2,QuizSession.rating(2,2,3,true));
        assertEquals(1,QuizSession.rating(2,1,3,true));
        assertEquals(1,QuizSession.rating(1,1,1,true));
        assertEquals(0,QuizSession.rating(2,1,3,false));
        for (int words : new int[]{1,5,10}) {
            var questions = new ArrayList<QuizSession.Question>();
            for (int i=0;i<words;i++) {
                questions.add(question(i,false));
                questions.add(question(i,true));
            }
            var quiz = new QuizSession(30,questions);
            while (!quiz.finished()) {
                quiz.answer(quiz.question().options().indexOf("wrong"),random);
                quiz.advance();
                assertTrue(quiz.answered()<=30);
            }
            assertEquals(words,quiz.grades().size());
            assertTrue(quiz.grades().stream().allMatch(grade -> grade.rating()==0));
        }
    }

    @Test public void resumedQuizKeepsAnsweredOptionWhenSerialized() throws Exception {
        var quiz = new QuizSession(10,List.of(question(1,false),question(1,true)));
        quiz.answer(1,random);
        var buffer = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.ObjectOutputStream(buffer)) { output.writeObject(quiz); }
        try (var input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(buffer.toByteArray()))) {
            var restored = (QuizSession)input.readObject();
            assertEquals(quiz.question(),restored.question());
            assertEquals(1,restored.selected());
            assertEquals(quiz.total(),restored.total());
            assertEquals(quiz.grades(),restored.grades());
            assertTrue(restored.advance());
        }
    }

    private static QuizSession.Word word(int id) {
        return new QuizSession.Word(id,id,1,0,"word "+id,"en");
    }

    private static QuizSession.Question question(int id,boolean reverse) {
        var word = word(id);
        return new QuizSession.Question(word,reverse,word.spelling(),"right",new ArrayList<>(List.of("right","wrong")),false);
    }
}
