package com.payneteasy.tailqueue;

import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

public class TailQueueRollCycleTest {

    private final TimeZone defaultTimeZone = TimeZone.getDefault();

    @After
    public void restoreTimeZone() {
        TimeZone.setDefault(defaultTimeZone);
    }

    @Test
    public void tenMinutelyTruncatesMinutes() {
        assertThat(bucket(TailQueueRollCycle.TEN_MINUTELY, "2026-08-17T10:00:00Z")).isEqualTo("20260817-1000");
        assertThat(bucket(TailQueueRollCycle.TEN_MINUTELY, "2026-08-17T10:09:59Z")).isEqualTo("20260817-1000");
        assertThat(bucket(TailQueueRollCycle.TEN_MINUTELY, "2026-08-17T10:10:00Z")).isEqualTo("20260817-1010");
        assertThat(bucket(TailQueueRollCycle.TEN_MINUTELY, "2026-08-17T10:59:59Z")).isEqualTo("20260817-1050");
    }

    @Test
    public void minutelyAndHourlyKeepTheirGranularity() {
        assertThat(bucket(TailQueueRollCycle.MINUTELY, "2026-08-17T10:09:59Z")).isEqualTo("20260817-1009");
        assertThat(bucket(TailQueueRollCycle.HOURLY  , "2026-08-17T10:59:59Z")).isEqualTo("20260817-10");
    }

    /**
     * The property the sender relies on: sorting file names by name must sort them by time.
     */
    @Test
    public void bucketNamesSortChronologically() {
        for (TailQueueRollCycle cycle : TailQueueRollCycle.values()) {

            // one full day, minute by minute, crossing every hour boundary and the day boundary
            Instant start    = Instant.parse("2026-08-17T23:00:00Z");
            String  previous = cycle.formatBucket(start);

            for (int minute = 1; minute <= 24 * 60; minute++) {
                Instant now     = start.plus(Duration.ofMinutes(minute));
                String  current = cycle.formatBucket(now);

                assertThat(current)
                        .as("%s: bucket at %s must not sort before the previous one %s", cycle, now, previous)
                        .isGreaterThanOrEqualTo(previous);

                previous = current;
            }
        }
    }

    @Test
    public void bucketsAreComputedInUtcRegardlessOfDefaultTimeZone() {
        Instant now = Instant.parse("2026-08-17T23:30:00Z");

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String inUtc = TailQueueRollCycle.MINUTELY.formatBucket(now);

        // a zone with a DST shift and a date rollover relative to UTC
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        String inNewYork = TailQueueRollCycle.MINUTELY.formatBucket(now);

        assertThat(inUtc).isEqualTo("20260817-2330");
        assertThat(inNewYork).isEqualTo(inUtc);
    }

    private static String bucket(TailQueueRollCycle aCycle, String aInstant) {
        return aCycle.formatBucket(Instant.parse(aInstant));
    }
}
