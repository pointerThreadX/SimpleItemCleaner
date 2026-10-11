package com.creator01.simpleitemcleaner.lifecycle;

/**
 * Pure state machine. No entity/world references, clock, or Minecraft dependency.
 * Ownership is conditional: foreign lifespan writes stop intervention for this entity.
 */
public final class LifecycleState {
    public static final int EXTENSION_TICKS = 1200;
    public static final int FORMAT_VERSION = 1;

    private boolean tracked;
    private boolean persistentAge;
    private boolean ownsLifespan;
    private boolean conflict;
    private boolean conflictReported;
    private int originalLifespan;
    private int expectedLifespan;
    private Integer pendingAge;

    public void track() {
        tracked = true;
        persistentAge = true;
    }

    /** Retain full-age serialization after rollback, especially for custom lifespans >32767 ticks. */
    public boolean needsAgePersistence() { return persistentAge; }

    public boolean isTracked() {
        return tracked;
    }

    public boolean hasConflict() {
        return conflict;
    }

    public void markConflict() {
        track();
        conflict = true;
    }

    public boolean reportConflictOnce() {
        if (!conflict || conflictReported) {
            return false;
        }
        conflictReported = true;
        return true;
    }

    public void observeLifespan(int currentLifespan) {
        if (ownsLifespan && currentLifespan != expectedLifespan) {
            markConflict();
        }
    }

    /** Returns a positive Forge extraLife value, or zero to leave the event alone. */
    public int extend(int age, int currentLifespan) {
        observeLifespan(currentLifespan);
        if (conflict || age < 0 || age < currentLifespan) {
            return 0;
        }
        long target = (long) age + EXTENSION_TICKS;
        long increment = target - currentLifespan;
        if (target > Integer.MAX_VALUE || increment <= 0 || increment > Integer.MAX_VALUE) {
            // Experimental boundary: do not wrap, reset age, or create a per-tick retry loop.
            markConflict();
            return 0;
        }
        track();
        if (!ownsLifespan) {
            originalLifespan = currentLifespan;
            ownsLifespan = true;
        }
        expectedLifespan = (int) target;
        return (int) increment;
    }

    /** Only reverse a value which still agrees with our recorded last extension. */
    public int release(int currentLifespan) {
        observeLifespan(currentLifespan);
        if (conflict) {
            return currentLifespan;
        }
        int restored = ownsLifespan ? originalLifespan : currentLifespan;
        ownsLifespan = false;
        tracked = false;
        return restored;
    }

    /** Never overwrite a distinguishable intervening age edit by another mod/command. */
    public int restoreLoadedAge(int observedAge) {
        if (pendingAge == null) {
            return observedAge;
        }
        int savedAge = pendingAge;
        pendingAge = null;
        if (observedAge == savedAge || observedAge == (short) savedAge) {
            return savedAge;
        }
        markConflict();
        return observedAge;
    }

    public Snapshot snapshot(int liveAge, int liveLifespan) {
        observeLifespan(liveLifespan);
        // Serialization can occur before a newly deserialized entity joins a world.
        int age = liveAge;
        if (pendingAge != null && (liveAge == pendingAge || liveAge == (short) pendingAge.intValue())) {
            age = pendingAge;
        }
        return new Snapshot(age, ownsLifespan, originalLifespan, expectedLifespan, conflict);
    }

    public void load(Snapshot snapshot) {
        track();
        pendingAge = snapshot.age;
        ownsLifespan = snapshot.ownsLifespan;
        originalLifespan = snapshot.originalLifespan;
        expectedLifespan = snapshot.expectedLifespan;
        conflict = snapshot.conflict;
        conflictReported = false;
    }

    public static final class Snapshot {
        public final int age;
        public final boolean ownsLifespan;
        public final int originalLifespan;
        public final int expectedLifespan;
        public final boolean conflict;

        public Snapshot(int age, boolean ownsLifespan, int originalLifespan,
                        int expectedLifespan, boolean conflict) {
            this.age = age;
            this.ownsLifespan = ownsLifespan;
            this.originalLifespan = originalLifespan;
            this.expectedLifespan = expectedLifespan;
            this.conflict = conflict;
        }
    }
}
