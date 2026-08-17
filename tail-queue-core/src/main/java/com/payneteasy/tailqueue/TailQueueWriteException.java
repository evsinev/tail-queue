package com.payneteasy.tailqueue;

/**
 * Thrown by {@link ITailQueueWriter#writeMessage(String)} when the writer runs in strict mode
 * and the message could not be persisted.
 */
public class TailQueueWriteException extends RuntimeException {

    public TailQueueWriteException(String aMessage, Throwable aCause) {
        super(aMessage, aCause);
    }

    public TailQueueWriteException(String aMessage) {
        super(aMessage);
    }
}
