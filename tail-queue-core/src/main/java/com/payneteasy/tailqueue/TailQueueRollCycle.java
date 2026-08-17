package com.payneteasy.tailqueue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public enum TailQueueRollCycle {

    MINUTELY     ("yyyyMMdd-HHmm",  1),
    TEN_MINUTELY ("yyyyMMdd-HHmm", 10),
    HOURLY       ("yyyyMMdd-HH"  , 60);

    private final DateTimeFormatter formatter;
    private final int               minutesInBucket;

    TailQueueRollCycle(String aPattern, int aMinutesInBucket) {
        formatter       = DateTimeFormatter.ofPattern(aPattern);
        minutesInBucket = aMinutesInBucket;
    }

    /**
     * Computes the name of the time bucket the instant belongs to.
     * <p>
     * Buckets are always computed in UTC, so they are immune to DST shifts, and their names
     * sort lexicographically in chronological order.
     *
     * @param aNow instant to compute the bucket for
     * @return bucket name, without file prefix and suffix
     */
    public String formatBucket(Instant aNow) {
        LocalDateTime utc = LocalDateTime.ofInstant(aNow, ZoneOffset.UTC);

        if (minutesInBucket > 1) {
            utc = utc.withMinute(utc.getMinute() / minutesInBucket * minutesInBucket);
        }

        return utc.format(formatter);
    }
}
