package com.creator01.simpleitemcleaner.cleanup;

import java.util.Locale;

public final class CleanupResultTest {
    private static int assertions;

    public static void main(String[] args) {
        duration(83000, "0,083");
        duration(1500000, "1,500");
        duration(52000, "0,052");
        duration(0, "0,000");
        duration(499, "0,000");
        duration(500, "0,001");
        duration(999999, "1,000");
        duration(1000000000, "1000,000");
        duration(Long.MAX_VALUE, "9223372036854,776");
        CleanupResult result = new CleanupResult(16, 3, 83000);
        String template = "[SimpleItemCleaner] Items removed: {count} | {entities} entities | {duration} ms";
        check(result.format(template).equals("[SimpleItemCleaner] Items removed: 16 | 3 entities | 0,083 ms"), "Requested example");
        check(result.format("{entities}/{count}; {duration}ms; {count}").equals("3/16; 0,083ms; 16"), "Custom/reordered/repeated placeholders");
        check(result.format("Fixed message").equals("Fixed message"), "Template without placeholders");
        check(result.format("").isEmpty(), "Empty template");
        check(result.format("{unknown}").equals("{unknown}"), "Unknown placeholders stay literal");
        check(new CleanupResult(0, 0, 52000).format(template)
                .equals("[SimpleItemCleaner] Items removed: 0 | 0 entities | 0,052 ms"), "Zero result");
        check(new CleanupResult(4294967294L, 2147483648L, 0).format("{count}:{entities}")
                .equals("4294967294:2147483648"), "Long unit and entity totals");
        Locale previous = Locale.getDefault();
        try {
            for (Locale locale : new Locale[] {Locale.US, Locale.GERMANY, new Locale("pt", "BR")}) {
                Locale.setDefault(locale);
                duration(1500000, "1,500");
            }
        } finally { Locale.setDefault(previous); }
        for (long[] invalid : new long[][] {{-1, 0, 0}, {0, -1, 0}, {0, 0, -1}}) {
            boolean rejected = false;
            try { new CleanupResult(invalid[0], invalid[1], invalid[2]); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Reject negative statistics");
        }
        System.out.println("CleanupResultTest: " + assertions + " assertions passed (long counters, milliseconds, templates).");
    }

    private static void duration(long nanos, String expected) {
        check(new CleanupResult(0, 0, nanos).durationMillis().equals(expected), "Duration " + nanos);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) { throw new AssertionError(message); }
    }
}
