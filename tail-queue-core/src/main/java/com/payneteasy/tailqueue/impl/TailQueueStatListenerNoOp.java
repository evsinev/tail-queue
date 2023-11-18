package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueStat;

public class TailQueueStatListenerNoOp implements ITailQueueStat {

    @Override
    public void onMessageWritten() {

    }

    @Override
    public void onMessageWriteError() {

    }
}
