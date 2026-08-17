package com.payneteasy.tailqueue;

public enum TailQueueFsyncPolicy {

    /** No explicit fsync: messages survive a process crash, but may be lost on power loss. */
    NONE,

    /**
     * Every message is forced to stable storage before {@link ITailQueueWriter#writeMessage(String)}
     * returns, and the active file is forced before it is rolled.
     */
    EVERY_MESSAGE
}
