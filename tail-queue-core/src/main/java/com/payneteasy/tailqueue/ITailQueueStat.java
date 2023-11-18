package com.payneteasy.tailqueue;

public interface ITailQueueStat {

    void onMessageWritten();

    void onMessageWriteError();

}
