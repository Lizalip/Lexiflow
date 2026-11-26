package app.lexiflow.ui;

import app.lexiflow.data.ReviewScheduler;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReviewSchedulerTest {
    @Test public void ratingsHaveDeterministicIntervals() {
        assertEquals(new ReviewScheduler.Next(600_100L,0),ReviewScheduler.basic(7,0,100));
        assertEquals(new ReviewScheduler.Next(86_400_100L,1),ReviewScheduler.basic(7,1,100));
        assertEquals(new ReviewScheduler.Next(14 * 86_400_000L + 100,14),ReviewScheduler.basic(7,2,100));
    }
    @Test public void intervalStartsAtTwoDaysAndStaysBounded() {
        assertEquals(2,ReviewScheduler.basic(0,2,0).days());
        assertEquals(365,ReviewScheduler.basic(365,2,0).days());
        assertThrows(IllegalArgumentException.class,() -> ReviewScheduler.basic(0,3,0));
    }

    @Test public void schedulingUsesSuppliedClockAndRejectsOverflow() {
        assertEquals(600_000L,ReviewScheduler.basic(0,0,0).due());
        assertEquals(599_000L,ReviewScheduler.basic(0,0,-1000).due());
        assertThrows(IllegalArgumentException.class,() -> ReviewScheduler.basic(-1,1,0));
        assertThrows(ArithmeticException.class,() -> ReviewScheduler.basic(0,1,Long.MAX_VALUE));
    }

    @Test public void successfulStreakRestartsAfterFailureAndUsesTimeOfScoring() {
        long overdue = 5 * 86_400_000L;
        var first = ReviewScheduler.basic(0,2,overdue);
        var second = ReviewScheduler.basic(first.days(),2,first.due());
        var failed = ReviewScheduler.basic(second.days(),0,second.due()+1);
        var recovered = ReviewScheduler.basic(failed.days(),2,failed.due());
        assertEquals(new ReviewScheduler.Next(overdue+2 * 86_400_000L,2),first);
        assertEquals(4,second.days());
        assertEquals(new ReviewScheduler.Next(second.due()+600_001L,0),failed);
        assertEquals(new ReviewScheduler.Next(failed.due()+2 * 86_400_000L,2),recovered);
    }

    @Test public void scheduleUsesElapsedDaysAcrossDaylightSavingChange() {
        long now = Instant.parse("2025-03-08T17:00:00Z").toEpochMilli();
        var due = ReviewScheduler.basic(0,1,now);
        assertEquals(86_400_000L,due.due()-now);
        var zone = ZoneId.of("America/New_York");
        assertEquals(12,Instant.ofEpochMilli(now).atZone(zone).getHour());
        assertEquals(13,Instant.ofEpochMilli(due.due()).atZone(zone).getHour());
    }
}
