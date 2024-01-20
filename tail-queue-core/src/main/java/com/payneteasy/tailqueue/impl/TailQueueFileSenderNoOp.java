package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueFileSender;
import com.payneteasy.tailqueue.TailQueueFileSenderContext;

import java.io.IOException;

public class TailQueueFileSenderNoOp implements ITailQueueFileSender {

    @Override
    public void sendFile(TailQueueFileSenderContext aContext) throws IOException {
    }

}
