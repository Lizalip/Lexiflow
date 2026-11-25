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
        version = 1, exportSchema = false)
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
        public boolean reverse;
        public long due;
        public int intervalDays;
        @NonNull public String spelling = "";
        @NonNull public String answer = "";
    }

    @Dao
    public abstract static class Library {
        @Query("SELECT * FROM saved ORDER BY id DESC") public abstract List<Saved> saved();
        @Query("SELECT * FROM collections ORDER BY name,id") public abstract List<Collection> collections();
        @Query("SELECT * FROM members ORDER BY collectionId,savedId") public abstract List<Member> memberships();
        @Insert(onConflict = OnConflictStrategy.IGNORE) public abstract long save(Saved item);
        @Query("UPDATE saved SET custom=:custom,note=:note WHERE id=:id") public abstract void edit(long id,String custom,String note);
        @Query("DELETE FROM saved WHERE id=:id") public abstract void remove(long id);
        @Insert(onConflict = OnConflictStrategy.IGNORE) public abstract long create(Collection collection);
        @Query("UPDATE OR IGNORE collections SET name=:name WHERE id=:id") public abstract int rename(long id,String name);
        @Insert(onConflict = OnConflictStrategy.IGNORE) public abstract long addMember(Member membership);
        @Query("DELETE FROM members WHERE collectionId=:collection AND savedId=:saved") public abstract void removeMember(long collection,long saved);
        @Insert(onConflict = OnConflictStrategy.IGNORE) public abstract long addCard(Card card);
        @Insert public abstract void addEvent(Event event);

        @Query("SELECT c.id,c.reverse,c.due,c.intervalDays,s.spelling, CASE WHEN s.custom='' THEN s.answer ELSE s.custom END AS answer "
                + "FROM cards c JOIN saved s ON s.id=c.savedId WHERE c.due<=:now ORDER BY c.due,c.id LIMIT 1")
        public abstract Exercise next(long now);
        @Query("SELECT count(*) FROM cards WHERE savedId IS NOT NULL") public abstract int cardCount();
        @Query("SELECT count(*) FROM cards WHERE savedId IS NOT NULL AND due<=:now") public abstract int dueCount(long now);
        @Query("SELECT count(*) FROM review_events") public abstract int reviewCount();
        @Query("SELECT count(*) FROM review_events WHERE rating>0") public abstract int successCount();
        @Query("UPDATE cards SET due=:next,intervalDays=:days WHERE id=:id AND due=:expected AND savedId IS NOT NULL")
        abstract int reschedule(long id,long expected,long next,int days);

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
