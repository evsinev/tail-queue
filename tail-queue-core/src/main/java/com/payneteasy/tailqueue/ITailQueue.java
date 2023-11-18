package com.payneteasy.tailqueue;

public interface ITailQueue {

    ITailQueueWriter getWriter();

    void startQueueSender();

    void shutdownQueueSender();

}
