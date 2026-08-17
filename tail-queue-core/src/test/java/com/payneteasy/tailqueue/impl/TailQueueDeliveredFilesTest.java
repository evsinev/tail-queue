package com.payneteasy.tailqueue.impl;

import org.junit.Test;

import static com.payneteasy.tailqueue.TailQueueDuplicatePolicy.RESEND;
import static com.payneteasy.tailqueue.TailQueueDuplicatePolicy.SKIP;
import static org.assertj.core.api.Assertions.assertThat;

public class TailQueueDeliveredFilesTest {

    private static final int MORE_THAN_MAX = 2_000;

    @Test
    public void remembersAFileUntilItIsConsumed() {
        TailQueueDeliveredFiles files = new TailQueueDeliveredFiles(SKIP);

        files.add("key");

        assertThat(files.consume("key")).isTrue();
        assertThat(files.consume("key")).as("an entry is consumed once").isFalse();
        assertThat(files.size()).isZero();
    }

    @Test
    public void remembersNothingWhenTheQueueResendsEveryFile() {
        TailQueueDeliveredFiles files = new TailQueueDeliveredFiles(RESEND);

        files.add("key");

        assertThat(files.size()).isZero();
        assertThat(files.consume("key")).isFalse();
    }

    /**
     * An entry which outlives its file could be matched by a new file with the same key, which would
     * be archived unsent. So the oldest entry is the one to lose when the record is full: the newest
     * ones are the files the dir sender is about to take.
     */
    @Test
    public void forgetsTheOldestEntryWhenItIsFull() {
        TailQueueDeliveredFiles files = new TailQueueDeliveredFiles(SKIP);

        for (int i = 0; i < MORE_THAN_MAX; i++) {
            files.add("key-" + i);
        }

        assertThat(files.size()).isLessThan(MORE_THAN_MAX);
        assertThat(files.consume("key-0")).as("the oldest entry was dropped").isFalse();
        assertThat(files.consume("key-" + (MORE_THAN_MAX - 1))).as("the newest entry is kept").isTrue();
    }
}
