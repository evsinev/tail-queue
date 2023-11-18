package com.payneteasy.tailqueue;

import java.time.format.DateTimeFormatter;

public enum TailQueueRollCycle {
    MINUTELY     ("yyyyMMdd-HHmm" ),
    TEN_MINUTELY ("yyyyMMdd-HHm"  ),
    HOURLY       ("yyyyMMdd-HH"   );

    private final DateTimeFormatter formatter;

    TailQueueRollCycle(String aPattern) {
        formatter = DateTimeFormatter.ofPattern(aPattern);
    }

    public DateTimeFormatter getDateFormatter() {
        return formatter;
    }
}
