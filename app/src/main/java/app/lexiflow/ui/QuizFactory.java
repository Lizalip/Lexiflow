package app.lexiflow.ui;

import app.lexiflow.data.LocalDictionaryRepository;
import app.lexiflow.data.PersonalDatabase;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

final class QuizFactory {
    private QuizFactory() {}

    static QuizSession create(LocalDictionaryRepository dictionary,List<PersonalDatabase.Exercise> eligible,
                              List<PersonalDatabase.Saved> saved,int limit,Random random) {
        var first = new ArrayList<QuizSession.Question>();
        var second = new ArrayList<QuizSession.Question>();
        var ids = new HashSet<String>();
        for (var row : saved) ids.add(row.entryId);
        var languages = dictionary.sourceLanguages(ids);
        int targets = (limit - limit/3)/2;
        boolean reverseFirst = random.nextBoolean();
        for (var exercise : eligible) {
            if (first.size() >= targets) break;
            var entry = dictionary.entry(exercise.entryId);
            if (entry == null) continue;
            String spelling = TranslationDisplay.russian(entry.spelling());
            List<String> answers = exercise.sourceAnswer
                    ? TranslationDisplay.alternatives(java.util.Arrays.asList(exercise.answer.split(" · ")))
                    : Collections.singletonList(exercise.answer.trim());
            String answer = answers.stream().filter(value -> !value.trim().isEmpty()).findFirst().orElse("");
            if (!validOption(answer)) continue;
            var accepted = new ArrayList<>(answers);
            accepted.addAll(TranslationDisplay.alternatives(dictionary.quizKnownTranslations(entry.language(),entry.spelling())));
            var word = new QuizSession.Word(exercise.id,exercise.savedId,exercise.due,exercise.intervalDays,spelling,entry.language());
            var forward = new ArrayList<String>();
            var reverse = new ArrayList<String>();
            boolean verifiedReverse = exercise.sourceAnswer || entry.senses().stream()
                    .filter(sense -> sense.id().equals(exercise.senseId))
                    .flatMap(sense -> ArticleDisplay.translations(sense,true).stream())
                    .anyMatch(value -> key(value).equals(key(answer)));
            // The shared dictionary checks all meanings of each candidate spelling for reverse ambiguity.
            for (var row : dictionary.quizCandidates(entry.language(),entry.pos(),entry.spelling(),null)) {
                if (!key(row.spelling()).equals(key(spelling))) forward.add(TranslationDisplay.russian(TranslationDisplay.wording(row.translation())));
            }
            if (verifiedReverse) for (var row : dictionary.quizCandidates(entry.language(),entry.pos(),entry.spelling(),answer)) {
                reverse.add(TranslationDisplay.russian(row.spelling()));
            }
            for (var row : saved) {
                if (!entry.language().equals(languages.get(row.entryId)) || key(row.spelling).equals(key(spelling))) continue;
                String value = row.custom.isEmpty() ? TranslationDisplay.answer(row.answer,true,true) : row.custom;
                forward.addAll(java.util.Arrays.asList(value.split(" · ")));
            }
            var direct = options(word,false,spelling,answer,accepted,forward,random);
            var inverse = options(word,true,answer,spelling,Collections.singletonList(spelling),reverse,random);
            if (direct == null && inverse == null) continue;
            if (direct == null || inverse == null) first.add(direct == null ? inverse : direct);
            else if (reverseFirst) { first.add(inverse); second.add(direct); }
            else { first.add(direct); second.add(inverse); }
            reverseFirst = !reverseFirst;
        }
        first.addAll(second);
        return new QuizSession(limit,first);
    }

    static QuizSession.Question options(QuizSession.Word word,boolean reverse,String prompt,String correct,
                                        List<String> accepted,List<String> pool,Random random) {
        Set<String> known = new HashSet<>();
        for (String answer : accepted) known.add(key(answer));
        known.add(key(correct));
        known.add(key(prompt));
        var result = new ArrayList<String>();
        result.add(correct);
        for (String candidate : pool) {
            String normalized = key(candidate);
            if (validOption(candidate) && !normalized.isEmpty() && known.add(normalized)) result.add(candidate);
            if (result.size()==4) break;
        }
        if (result.size()<2) return null;
        Collections.shuffle(result,random);
        return new QuizSession.Question(word,reverse,prompt,correct,result,false);
    }

    private static boolean validOption(String text) {
        return !text.trim().isEmpty() && !text.contains("[[") && !text.contains("{{");
    }

    private static String key(String text) {
        return Normalizer.normalize(TranslationDisplay.russian(text),Normalizer.Form.NFC)
                .trim().toLowerCase(Locale.ROOT);
    }
}
