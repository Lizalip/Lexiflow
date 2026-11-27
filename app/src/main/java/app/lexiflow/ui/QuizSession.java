package app.lexiflow.ui;

import app.lexiflow.data.PersonalDatabase.Library.QuizGrade;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

/** A bounded local multiple-choice session saved with the ViewModel. */
final class QuizSession implements Serializable {
    private static final long serialVersionUID = 1L;

    record Word(long card,long saved,long due,int days,String spelling,String language) implements Serializable {}
    record Question(Word word,boolean reverse,String prompt,String correct,List<String> options,boolean retry)
            implements Serializable {
        Question repeated(Random random) {
            var shuffled = new ArrayList<>(options);
            Collections.shuffle(shuffled,random);
            if (shuffled.equals(options)) Collections.rotate(shuffled,1);
            return new Question(word,reverse,prompt,correct,shuffled,true);
        }
    }
    private record Attempt(Word word,boolean reverse,boolean correct) implements Serializable {}

    private final int limit;
    private final ArrayList<Question> queue;
    private final ArrayList<Attempt> attempts = new ArrayList<>();
    private int position;
    private int selected = -1;
    private boolean saved;
    private Long nextDue;

    QuizSession(int limit,List<Question> questions) {
        this.limit = limit;
        queue = new ArrayList<>(questions);
    }

    Question question() { return position < queue.size() ? queue.get(position) : null; }
    int position() { return position; }
    int total() { return queue.size(); }
    int selected() { return selected; }
    boolean finished() { return question() == null; }
    boolean saved() { return saved; }
    void markSaved(Long nextDue) { saved = true; this.nextDue = nextDue; }
    Long nextDue() { return nextDue; }

    boolean answer(int index,Random random) {
        var question = question();
        if (question == null || selected >= 0 || index < 0 || index >= question.options().size()) return false;
        selected = index;
        boolean correct = question.correct().equals(question.options().get(index));
        attempts.add(new Attempt(question.word(),question.reverse(),correct));
        if (!correct && !question.retry() && queue.size() < limit) {
            int retry = Math.min(position+3,queue.size());
            if (position+1 < queue.size() && retry < queue.size()
                    && queue.get(retry).word().saved() == question.word().saved()) retry++;
            queue.add(retry,question.repeated(random));
        }
        return true;
    }

    boolean advance() {
        if (selected < 0 || finished()) return false;
        position++;
        selected = -1;
        return true;
    }

    int answered() { return attempts.size(); }
    int correct() { return (int)attempts.stream().filter(Attempt::correct).count(); }
    int uniqueWords() {
        return (int)attempts.stream().map(row -> row.word().language()+"/"+row.word().spelling()).distinct().count();
    }

    static int rating(int attempts,int correct,int directions,boolean lastCorrect) {
        if (!lastCorrect) return 0;
        return attempts == correct && directions == 3 ? 2 : 1;
    }

    List<QuizGrade> grades() {
        var grouped = new LinkedHashMap<Word,List<Attempt>>();
        for (var attempt : attempts) grouped.computeIfAbsent(attempt.word(),key -> new ArrayList<>()).add(attempt);
        var result = new ArrayList<QuizGrade>();
        for (var row : grouped.entrySet()) {
            var word = row.getKey();
            int directions = 0;
            int correct = 0;
            for (var attempt : row.getValue()) {
                directions |= attempt.reverse() ? 2 : 1;
                if (attempt.correct()) correct++;
            }
            int count = row.getValue().size();
            int rating = rating(count,correct,directions,row.getValue().get(count-1).correct());
            result.add(new QuizGrade(word.card(),word.saved(),word.due(),word.days(),rating));
        }
        return result;
    }
}
