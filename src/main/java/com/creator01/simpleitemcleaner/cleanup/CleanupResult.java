package com.creator01.simpleitemcleaner.cleanup;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Immutable result of one cleanup. Timing excludes log/message formatting and broadcasting. */
public final class CleanupResult {
    public final long units;
    public final long entities;
    public final long durationNanos;

    public CleanupResult(long units, long entities, long durationNanos) {
        if (units < 0 || entities < 0 || durationNanos < 0) {
            throw new IllegalArgumentException("Cleanup statistics must be nonnegative");
        }
        this.units = units;
        this.entities = entities;
        this.durationNanos = durationNanos;
    }

    public String durationMillis() {
        // One millisecond = 1,000,000 nanoseconds. Exact decimal conversion, independent of JVM locale.
        return BigDecimal.valueOf(durationNanos, 6).setScale(3, RoundingMode.HALF_UP)
                .toPlainString().replace('.', ',');
    }

    public String format(String template) {
        return template.replace("{count}", Long.toString(units))
                .replace("{entities}", Long.toString(entities))
                .replace("{duration}", durationMillis());
    }
}
