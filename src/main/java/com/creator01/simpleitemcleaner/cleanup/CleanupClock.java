package com.creator01.simpleitemcleaner.cleanup;

import java.util.HashSet;
import java.util.Set;

/** One counter per server session, never per dimension; caller resets after completed cleanup. */
public final class CleanupClock {
    private final long intervalTicks;
    private final Set<Long> warningTicks = new HashSet<>();
    private long remainingTicks;

    public CleanupClock(int intervalSeconds, int[] warnings) {
        if (intervalSeconds <= 0) { throw new IllegalArgumentException("Positive interval required"); }
        intervalTicks = intervalSeconds * 20L;
        for (int warning : warnings) {
            if (warning > 0 && warning < intervalSeconds) { warningTicks.add(warning * 20L); }
        }
        reset();
    }

    /** -1 means cleanup due; positive means warning seconds; zero means no action. */
    public int tick() {
        if (remainingTicks > 0) { remainingTicks--; }
        if (remainingTicks == 0) { return -1; }
        return warningTicks.contains(remainingTicks) ? (int) (remainingTicks / 20) : 0;
    }

    public void reset() { remainingTicks = intervalTicks; }
    public long remainingSeconds() { return (remainingTicks + 19) / 20; }
}
