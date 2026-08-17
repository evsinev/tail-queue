package com.payneteasy.tailqueue.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Clock which can be moved forwards and backwards, to reproduce roll cycles and NTP steps.
 */
public class MutableClock extends Clock {

    private Instant now;

    public MutableClock(String aInstant) {
        now = Instant.parse(aInstant);
    }

    public void set(String aInstant) {
        now = Instant.parse(aInstant);
    }

    public void plusMinutes(long aMinutes) {
        now = now.plus(Duration.ofMinutes(aMinutes));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId aZone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
