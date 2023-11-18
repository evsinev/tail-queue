package com.payneteasy.tailqueue;

public interface ITailQueueWriter {

    /**
     * Write message to queue
     *
     * @param aMessage message should not contain the new line '\n' character
     */
    void writeMessage(String aMessage);


}
