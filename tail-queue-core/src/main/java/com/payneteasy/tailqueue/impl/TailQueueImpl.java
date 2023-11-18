package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueue;
import com.payneteasy.tailqueue.ITailQueueSender;
import com.payneteasy.tailqueue.ITailQueueWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class TailQueueImpl implements ITailQueue {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueImpl.class );

    private final ITailQueueWriter    writer;
    private final TailQueueSenderTask senderTask;
    private final Thread              thread;

    public TailQueueImpl(ITailQueueWriter writer, TailQueueSenderTask senderTask) {
        this.writer     = writer;
        this.senderTask = senderTask;

        thread = new Thread(senderTask);
    }

    @Override
    public ITailQueueWriter getWriter() {
        return writer;
    }

    @Override
    public void startQueueSender() {
        thread.start();
    }

    @Override
    public void shutdownQueueSender() {
        LOG.debug("Shutting down...");
        thread.interrupt();
    }
}
