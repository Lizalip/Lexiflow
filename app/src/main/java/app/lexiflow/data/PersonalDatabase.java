package app.lexiflow.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import androidx.room.Transaction;
import java.util.List;

@Database(entities = {PersonalDatabase.Saved.class, PersonalDatabase.Collection.class,
        PersonalDatabase.Member.class, PersonalDatabase.Card.class, PersonalDatabase.Event.class},
        version = 1, exportSchema = true)
public abstract class PersonalDatabase extends RoomDatabase {
    public abstract Library library();

    @Entity(tableName = "saved", indices = @Index(value = {"entryId","senseId"}, unique = true))
    public static class Saved {
        @PrimaryKey(autoGenerate = true) public long id;
        @NonNull public String entryId = "";
        @NonNull public String senseId = ""; // Empty denotes an entry-level save.
        @NonNull public String spelling = "";
        @NonNull public String answer = "";
        @NonNull public String custom = "";
        @NonNull public String note = "";
    }

    @Entity(tableName = "collections", indices = @Index(value = "name", unique = true))
    public static class Collection {
        @PrimaryKey(autoGenerate = true) public long id;
        @NonNull public String name = "";
    }

    @Entity(tableName = "members", primaryKeys = {"collectionId","savedId"}, indices = @Index("savedId"),
            foreignKeys = {
                @ForeignKey(entity = Collection.class, parentColumns = "id", childColumns = "collectionId", onDelete = ForeignKey.CASCADE),
                @ForeignKey(entity = Saved.class, parentColumns = "id", childColumns = "savedId", onDelete = ForeignKey.CASCADE)
            })
    public static class Member {
        public long collectionId;
        public long savedId;
    }

    @Entity(tableName = "cards", foreignKeys = @ForeignKey(entity = Saved.class, parentColumns = "id",
            childColumns = "savedId", onDelete = ForeignKey.SET_NULL),
            indices = {@Index(value = {"savedId","reverse"}, unique = true), @Index("due")})
    public static class Card {
        @PrimaryKey(autoGenerate = true) public long id;
        public Long savedId;
        public boolean reverse;
        public long due;
        public int intervalDays;
    }

    @Entity(tableName = "review_events", foreignKeys = @ForeignKey(entity = Card.class, parentColumns = "id",
            childColumns = "cardId"), indices = @Index("cardId"))
    public static class Event {
        @PrimaryKey(autoGenerate = true) public long id;
        public long cardId;
        public long reviewedAt;
        public int rating;
    }

    public static class Exercise {
        public long id;
        public long savedId;
        public boolean reverse;
        public long due;
        public int intervalDays;
        @NonNull public String entryId = "";
        @NonNull public String senseId = "";
        @NonNull public String spelling = "";
        @NonNull public String answer = "";
        public boolean sourceAnswer;
    }

    public static class LearningCounts {
        public int cards;
        public int unstudied;
        public int studied;
        public int ready;
        public Long nextDue;
    }

    public static class CollectionCount {
        public long collectionId;
        public int words;
    }

    @Dao
    public abstract static class Library {
        @Query("SELECT * FROM saved ORDER BY id DESC") public abstract List<Saved> saved();
        @Query("SELECT * FROM saved ORDER BY id DESC LIMIT :limit OFFSET :offset")
        public abstract List<Saved> savedPage(int limit,int offset);
        @Query("SELECT count(*) FROM saved") public abstract int savedCount();
        private static final String SAVED_FILTER = " FROM saved WHERE (:notesOnly = 0 OR note <> '') "
                + "AND (:pattern IS NULL OR spelling GLOB :pattern OR answer GLOB :pattern "
                + "OR custom GLOB :pattern OR note GLOB :pattern)";
        @Query("SELECT count(*)" + SAVED_FILTER)
        public abstract int filteredSavedCount(boolean notesOnly,String pattern);
        @Query("SELECT *" + SAVED_FILTER + " ORDER BY CASE WHEN :alphabetical THEN spelling END COLLATE NOCASE,"
                + " id DESC LIMIT :limit OFFSET :offset")
        public abstract List<Saved> filteredSavedPage(boolean notesOnly,String pattern,boolean alphabetical,int limit,int offset);
        @Query("SELECT saved.* FROM members JOIN saved ON saved.id=members.savedId "
                + "WHERE members.collectionId=:collection ORDER BY members.savedId DESC LIMIT :limit OFFSET :offset")
        public abstract List<Saved> collectionPage(long collection,int limit,int offset);
        @Query("SELECT count(*) FROM members WHERE collectionId=:collection") public abstract int collectionSize(long collection);
        @Query("SELECT * FROM saved s WHERE NOT EXISTS (SELECT 1 FROM members m WHERE m.collectionId=:collection "
                + "AND m.savedId=s.id) ORDER BY s.id DESC LIMIT :limit")
        public abstract List<Saved> recentOutsideCollection(long collection,int limit);
        @Query("SELECT * FROM collections ORDER BY name,id") public abstract List<Collection> collections();
        @Query("SELECT collectionId, count(*) AS words FROM members GROUP BY collectionId")
        public abstract List<CollectionCount> collectionCounts();
        @Query("SELECT * FROM members ORDER BY collectionId,savedId") public abstract List<Member> memberships();
        @Insert(onConflict = OnConflictStrategy.IGNORE) public abstract long save(Saved item);
        @Query("UPDATE saved SET custom=:custom,note=:note WHERE id=:id") public abstract int edit(long id,String custom,String note);
        @Query("DELETE FROM saved WHERE id=:id") public abstract void remove(long id);
        @Insert(onConflict = OnConflictStrategy.IGNORE) public abstract long create(Collection collection);
        @Query("UPDATE OR IGNORE collections SET name=:name WHERE id=:id") public abstract int rename(long id,String name);
        @Query("DELETE FROM collections WHERE id=:id") public abstract int deleteCollection(long id);
        @Insert(onConflict = OnConflictStrategy.IGNORE) public abstract long addMember(Member membership);
        @Query("DELETE FROM members WHERE collectionId=:collection AND savedId=:saved") public abstract void removeMember(long collection,long saved);
        @Insert(onConflict = OnConflictStrategy.IGNORE) public abstract long addCard(Card card);
        @Insert public abstract void addEvent(Event event);

        @Query("SELECT id FROM saved WHERE entryId=:entry AND senseId=:sense")
        public abstract long savedId(String entry,String sense);

        @Query("SELECT EXISTS(SELECT 1 FROM cards WHERE savedId=:saved)")
        public abstract boolean hasStudyCard(long saved);

        @Query("SELECT id FROM cards WHERE savedId=:saved AND reverse=0")
        abstract long forwardCard(long saved);

        @Transaction
        public long learn(Saved item,long now) {
            long saved = save(item);
            var card = new Card();
            card.savedId = saved == -1 ? savedId(item.entryId,item.senseId) : saved;
            card.due = now;
            long id = addCard(card);
            return id == -1 ? forwardCard(card.savedId) : id;
        }

        private static final String STUDIED = "EXISTS (SELECT 1 FROM review_events e WHERE e.cardId=c.id)";
        private static final String LEARNED = "EXISTS (SELECT 1 FROM cards old JOIN review_events e ON e.cardId=old.id"
                + " WHERE old.savedId=s.id)";
        private static final String HAS_ANSWER = "(trim(s.custom)<>'' OR (s.custom='' AND trim(s.answer)<>''))";
        @Query("SELECT count(*) AS cards, count(CASE WHEN NOT learned THEN 1 END) AS unstudied,"
                + "count(CASE WHEN learned THEN 1 END) AS studied,"
                + "count(CASE WHEN learned AND due<=:now THEN 1 END) AS ready,"
                + "min(CASE WHEN due>:now THEN due END) AS nextDue FROM (SELECT s.id,min(c.due) AS due,"
                + LEARNED+" AS learned FROM saved s LEFT JOIN cards c ON c.savedId=s.id"
                + " WHERE "+HAS_ANSWER+" GROUP BY s.id)")
        public abstract LearningCounts learningCounts(long now);

        @Query("SELECT c.id FROM cards c WHERE c.savedId IS NOT NULL AND c.due<=:now "
                + "AND (:mode=0 OR (:mode=1 AND NOT "+STUDIED+") OR (:mode=2 AND "+STUDIED+")) "
                + "AND (:collection=0 OR EXISTS (SELECT 1 FROM members m WHERE m.collectionId=:collection AND m.savedId=c.savedId)) "
                + "ORDER BY c.due,c.id LIMIT :limit")
        public abstract List<Long> learningQueue(long now,int mode,long collection,int limit);

        private static final String EXERCISE = "SELECT c.id,s.id AS savedId,c.reverse,c.due,c.intervalDays,s.entryId,s.senseId,s.spelling,"
                + "CASE WHEN s.custom='' THEN s.answer ELSE s.custom END AS answer, s.custom='' AS sourceAnswer "
                + "FROM cards c JOIN saved s ON s.id=c.savedId ";
        @Query(EXERCISE+"WHERE c.id=:id AND c.due<=:now")
        public abstract Exercise learningExercise(long id,long now);

        @Query(EXERCISE+"WHERE c.id=:id")
        public abstract Exercise quizExercise(long id);

        private static final String COLLECTION_FILTER =
                "(:collection=0 OR EXISTS (SELECT 1 FROM members m WHERE m.collectionId=:collection AND m.savedId=s.id))";

        @Query("INSERT OR IGNORE INTO cards (savedId,reverse,due,intervalDays) "
                +"SELECT s.id,0,:now,0 FROM saved s WHERE "+HAS_ANSWER+" AND "+COLLECTION_FILTER
                +" AND NOT EXISTS (SELECT 1 FROM cards c WHERE c.savedId=s.id) ORDER BY s.id LIMIT :limit")
        abstract void prepareMissingQuizCards(long now,long collection,int limit);

        @Transaction
        public List<Exercise> quizQueue(long now,int mode,long collection,int limit) {
            if (mode == 0 || mode == 1) prepareMissingQuizCards(now,collection,limit);
            return quizExercises(mode,collection,limit);
        }

        @Query(EXERCISE+"WHERE "+HAS_ANSWER+" "
                +"AND c.id=(SELECT sibling.id FROM cards sibling WHERE sibling.savedId=s.id "
                +"ORDER BY sibling.due,sibling.id LIMIT 1) "
                +"AND (:mode=0 OR (:mode=1 AND NOT "+LEARNED+") OR (:mode=2 AND "+LEARNED+")) "
                +"AND "+COLLECTION_FILTER+" "
                +"ORDER BY c.due,c.id LIMIT :limit")
        abstract List<Exercise> quizExercises(int mode,long collection,int limit);

        @Query("SELECT c.id,s.id AS savedId,c.reverse,c.due,c.intervalDays,s.entryId,s.senseId,s.spelling, CASE WHEN s.custom='' THEN s.answer ELSE s.custom END AS answer, "
                + "s.custom='' AS sourceAnswer "
                + "FROM cards c JOIN saved s ON s.id=c.savedId WHERE c.due<=:now ORDER BY c.due,c.id LIMIT 1")
        public abstract Exercise next(long now);
        @Query("SELECT count(*) FROM cards WHERE savedId IS NOT NULL") public abstract int cardCount();
        @Query("SELECT count(*) FROM cards WHERE savedId IS NOT NULL AND due<=:now") public abstract int dueCount(long now);
        @Query("SELECT count(*) FROM review_events") public abstract int reviewCount();
        @Query("SELECT count(*) FROM review_events WHERE rating>0") public abstract int successCount();
        @Query("SELECT reviewedAt FROM review_events WHERE reviewedAt>=:start AND reviewedAt<:end")
        public abstract List<Long> reviewTimes(long start,long end);
        @Query("UPDATE cards SET due=:next,intervalDays=:days WHERE id=:id AND due=:expected AND savedId IS NOT NULL")
        abstract int reschedule(long id,long expected,long next,int days);

        @Query("UPDATE cards SET due=:next,intervalDays=:days WHERE savedId=:saved AND id<>:primary AND due<=:now")
        abstract void alignDueCards(long saved,long primary,long now,long next,int days);

        public record QuizGrade(long card,long saved,long expectedDue,int days,int rating) {}

        public void gradeQuiz(List<QuizGrade> grades,long now) {
            gradeQuiz(grades,now,now);
        }

        @Transaction
        public void gradeQuiz(List<QuizGrade> grades,long now,long startedAt) {
            // A card studied early stays voluntary even if it comes due during the quiz.
            long cutoff = Math.min(now,startedAt);
            for (var grade : grades) {
                if (grade.expectedDue()>cutoff) continue;
                var next = ReviewScheduler.basic(grade.days(),grade.rating(),now);
                if (reschedule(grade.card(),grade.expectedDue(),next.due(),next.days()) != 1) continue;
                alignDueCards(grade.saved(),grade.card(),cutoff,next.due(),next.days());
                var event = new Event();
                event.cardId = grade.card();
                event.reviewedAt = now;
                event.rating = grade.rating();
                addEvent(event);
            }
        }

        @Transaction
        public boolean grade(Exercise exercise,int rating,long now,ReviewScheduler scheduler) {
            if (exercise.due > now) return false;
            var next = scheduler.schedule(exercise.intervalDays,rating,now);
            if (reschedule(exercise.id,exercise.due,next.due(),next.days()) != 1) return false;
            Event event = new Event();
            event.cardId = exercise.id;
            event.reviewedAt = now;
            event.rating = rating;
            addEvent(event);
            return true;
        }
    }
}
