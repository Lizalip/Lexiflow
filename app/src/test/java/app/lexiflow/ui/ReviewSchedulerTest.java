package app.lexiflow.ui;

import app.lexiflow.data.ReviewScheduler;
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
}
