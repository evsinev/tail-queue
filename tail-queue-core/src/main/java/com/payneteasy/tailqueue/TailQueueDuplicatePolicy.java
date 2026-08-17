package com.payneteasy.tailqueue;

/**
 * What the sender does with a closed file whose lines the tailer has already delivered while the
 * file was still being written.
 * <p>
 * Delivery stays at-least-once in both modes: neither of them can make a message be delivered
 * zero times. They differ only in how many copies a consumer sees in the normal case.
 */
public enum TailQueueDuplicatePolicy {

    /**
     * The dir sender sends every closed file in full, even the one the tailer has just delivered
     * line by line. A message written to a rolled file is therefore delivered twice.
     * <p>
     * This is the behaviour of every release before the mode existed, and the default.
     */
    RESEND,

    /**
     * A closed file which the tailer delivered up to its end of file is archived without being
     * sent again, so a message is delivered once in the normal case.
     * <p>
     * Duplicates still happen and consumers must stay idempotent: a process restart re-delivers a
     * partially tailed file, a failed send makes the whole file be sent again, and a file the
     * tailer never read is always sent in full.
     */
    SKIP
}
