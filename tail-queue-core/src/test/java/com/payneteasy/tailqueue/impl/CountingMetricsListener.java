package com.payneteasy.tailqueue.impl;

public class CountingMetricsListener extends TailQueueMetricsListenerListenerNoOp {

    public int writeMessageSuccess;
    public int writeMessageError;
    public int senderDirArchiveFile;
    public int senderDirQuarantineFile;

    @Override
    public void didWriteMessageSuccess() {
        writeMessageSuccess++;
    }

    @Override
    public void didWriteMessageError() {
        writeMessageError++;
    }

    @Override
    public void didSenderDirArchiveFile() {
        senderDirArchiveFile++;
    }

    @Override
    public void didSenderDirQuarantineFile() {
        senderDirQuarantineFile++;
    }
}
