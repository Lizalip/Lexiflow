package app.lexiflow.data;

/** Experimental deterministic schedule. Ratings 0, 1 and 2 mean forgot, hard and knew. */
public interface ReviewScheduler {
    record Next(long due, int days) {}
    Next schedule(int previousDays, int rating, long now);

    static Next basic(int previousDays, int rating, long now) {
        if (previousDays < 0 || previousDays > 365 || rating < 0 || rating > 2) {
            throw new IllegalArgumentException("invalid review state");
        }
        if (rating == 0) return new Next(Math.addExact(now,600_000L),0);
        int days = rating == 1 ? 1 : Math.min(365,Math.max(2,previousDays * 2));
        return new Next(Math.addExact(now,days * 86_400_000L),days);
    }
}
