package com.creator01.simpleitemcleaner.lifecycle;

/** Executable JDK-only behavioral tests; failures always fail the Gradle task. */
public final class LifecycleStateTest {
    private static int assertions;

    public static void main(String[] args) {
        disabledAndUnexpired();
        naturalExpirationCadence();
        customLifespans();
        saveReloadBoundaries();
        saveBetweenExpirations();
        rollbackAfterReload();
        unloadDoesNotAge();
        foreignLifespanAndAgeChanges();
        mergeAgeIsNotOverwritten();
        arithmeticAndSentinels();
        snapshotIsolation();
        System.out.println("LifecycleStateTest: " + assertions + " assertions passed (11 scenarios).");
    }

    private static void disabledAndUnexpired() {
        LifecycleState state = new LifecycleState();
        check(!state.isTracked(), "Fresh entity has no tracked age or override");
        equal(6000, state.release(6000), "Disabled preserves default lifespan");
        equal(19200, state.release(19200), "Disabled preserves custom lifespan");
        equal(0, state.extend(100, 6000), "Do not extend before expiration");
        check(!state.isTracked(), "Reading an unexpired entity does not track it");
    }

    private static void naturalExpirationCadence() {
        LifecycleState state = new LifecycleState();
        int lifespan = 6000;
        int events = 0;
        for (int age = 1; age <= 30000; age++) {
            if (age >= lifespan) {
                int extra = state.extend(age, lifespan);
                equal(1200, extra, "Ordinary extension is sixty seconds");
                lifespan += extra; // The actual Forge post-event operation.
                events++;
            }
        }
        equal(21, events, "One expiration per 1200 ticks, never one per tick");
        equal(31200, lifespan, "Finite future deadline");
        equal(6000, state.release(lifespan), "Repeated extensions restore the first original deadline");
    }

    private static void customLifespans() {
        for (int original : new int[] {1, 200, 6000, 12000, 40000, 1000000}) {
            LifecycleState state = new LifecycleState();
            int life = original;
            for (int i = 0; i < 5; i++) {
                life += state.extend(life, life);
            }
            equal(original, state.release(life), "Restore exact custom lifespan " + original);
        }
        LifecycleState overdue = new LifecycleState();
        equal(8200, overdue.extend(13000, 6000), "Overdue age must still get 1200 ticks of runway");
        equal(6000, overdue.release(14200), "Overdue restoration is not clamped to age");
    }

    private static void saveReloadBoundaries() {
        for (int fullAge : new int[] {0, 32766, 32767, 32768, 40000, 65535, 65536,
                100000, 1000000, Integer.MAX_VALUE - 2000, -32768}) {
            LifecycleState source = new LifecycleState();
            source.track();
            LifecycleState loaded = reload(source, fullAge, Integer.MAX_VALUE);
            equal(fullAge, loaded.restoreLoadedAge((short) fullAge),
                    "Restore full int rather than vanilla short: " + fullAge);
            equal(25, loaded.restoreLoadedAge(25), "Consume saved age exactly once");
        }
    }

    private static void saveBetweenExpirations() {
        LifecycleState state = new LifecycleState();
        int life = 6000 + state.extend(6000, 6000);
        LifecycleState loaded = reload(state, 6791, life);
        equal(6791, loaded.restoreLoadedAge((short) 6791), "Save current age, not last expiration age");
        check(!loaded.hasConflict(), "Ordinary load is not an age conflict");
        LifecycleState.Snapshot snapshot = loaded.snapshot(6850, life);
        equal(6850, snapshot.age, "Subsequent saves observe subsequent age");
    }

    private static void rollbackAfterReload() {
        LifecycleState active = new LifecycleState();
        int original = 41000;
        int life = original + active.extend(original, original);
        LifecycleState disabled = reload(active, 41999, life);
        equal(41999, disabled.restoreLoadedAge((short) 41999), "Restore full age even when disabled");
        equal(original, disabled.release(life), "Rollback persisted extension with prototype off");
        check(!disabled.isTracked(), "Successful rollback stops producing lifecycle state");
        equal(original, disabled.release(original), "Rollback is idempotent");
        check(41999 >= original, "Normal Forge expiry becomes due after rollback");

        LifecycleState noExtension = new LifecycleState();
        noExtension.track();
        LifecycleState loaded = reload(noExtension, 40000, 120000);
        equal(40000, loaded.restoreLoadedAge((short) 40000), "Preserve age without an extension");
        equal(120000, loaded.release(120000), "Do not change custom life if we never extended it");
    }

    private static void unloadDoesNotAge() {
        LifecycleState active = new LifecycleState();
        int life = 6000 + active.extend(6000, 6000);
        LifecycleState.Snapshot onDisk = active.snapshot(6600, life);
        LifecycleState loaded = new LifecycleState();
        loaded.load(onDisk); // No wall-clock input, so time offline cannot change the value.
        equal(6600, loaded.restoreLoadedAge((short) 6600), "Unloaded/offline time does not increase age");
        equal(life, loaded.snapshot(6600, life).expectedLifespan, "Unloading preserves the deadline");
    }

    private static void foreignLifespanAndAgeChanges() {
        LifecycleState state = new LifecycleState();
        state.extend(6000, 6000);
        equal(9500, state.release(9500), "Do not overwrite a foreign lifespan write");
        check(state.hasConflict(), "Foreign lifespan write is detected");
        equal(0, state.extend(9500, 9500), "Conflicting entity is no longer extended");
        check(state.reportConflictOnce(), "First conflict produces a diagnostic");
        check(!state.reportConflictOnce(), "No repeated diagnostic for the same entity");

        LifecycleState loaded = new LifecycleState();
        loaded.load(new LifecycleState.Snapshot(40000, false, 0, 0, false));
        equal(50, loaded.restoreLoadedAge(50), "Preserve a distinguishable foreign age edit");
        check(loaded.hasConflict(), "Foreign age write disables intervention");

        LifecycleState alreadyFull = new LifecycleState();
        alreadyFull.load(new LifecycleState.Snapshot(40000, false, 0, 0, false));
        equal(40000, alreadyFull.restoreLoadedAge(40000), "Accept another mod preserving the same full age");
        check(!alreadyFull.hasConflict(), "Already restored full age is not a conflict");
    }

    private static void mergeAgeIsNotOverwritten() {
        LifecycleState survivor = new LifecycleState();
        int life = 6000 + survivor.extend(6000, 6000);
        // Model vanilla choosing the younger age on merge; no actual game is launched here.
        LifecycleState merged = reload(survivor, 20, life);
        equal(20, merged.restoreLoadedAge(20), "Serialize the survivor's current younger age");
        equal(6000, merged.release(life), "Merge-age change does not change the original deadline");
    }

    private static void arithmeticAndSentinels() {
        for (int age : new int[] {-32768, -1}) {
            LifecycleState state = new LifecycleState();
            equal(0, state.extend(age, -40000), "Do not reinterpret a negative age/sentinel");
        }
        LifecycleState edge = new LifecycleState();
        equal(1200, edge.extend(Integer.MAX_VALUE - 1200, Integer.MAX_VALUE - 1200),
                "Largest representable target is checked with long arithmetic");
        LifecycleState overflow = new LifecycleState();
        equal(0, overflow.extend(Integer.MAX_VALUE - 1199, Integer.MAX_VALUE - 1199),
                "Overflow is refused");
        check(overflow.hasConflict(), "Overflow boundary produces a diagnostic");
        LifecycleState hugeDelta = new LifecycleState();
        equal(0, hugeDelta.extend(5000, Integer.MIN_VALUE), "Unrepresentable extraLife is refused");
        equal(Integer.MIN_VALUE, hugeDelta.release(Integer.MIN_VALUE), "Never replace custom values with 6000");
    }

    private static void snapshotIsolation() {
        LifecycleState state = new LifecycleState();
        state.extend(6000, 6000);
        LifecycleState.Snapshot first = state.snapshot(6100, 7200);
        state.extend(7200, 7200);
        equal(7200, first.expectedLifespan, "Existing immutable snapshot is not changed by later extensions");
        LifecycleState pending = new LifecycleState();
        pending.load(new LifecycleState.Snapshot(40000, false, 0, 0, false));
        equal(40000, pending.snapshot((short) 40000, 50000).age,
                "Save before join does not lose a pending full age");
    }

    private static LifecycleState reload(LifecycleState state, int age, int life) {
        LifecycleState loaded = new LifecycleState();
        loaded.load(state.snapshot(age, life));
        return loaded;
    }

    private static void equal(int expected, int actual, String label) {
        assertions++;
        if (expected != actual) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
