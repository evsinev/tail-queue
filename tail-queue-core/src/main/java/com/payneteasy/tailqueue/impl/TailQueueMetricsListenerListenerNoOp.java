package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueMetricsListener;

public class TailQueueMetricsListenerListenerNoOp implements ITailQueueMetricsListener {

    @Override
    public void didWriteMessageSuccess() {

    }

    @Override
    public void didWriteMessageError() {

    }

    @Override
    public void didSenderTaskErrorProcessingDir() {

    }

    @Override
    public void didSenderDirArchiveFile() {

    }

    @Override
    public void didSenderDirSendFile(int current, int count) {

    }

    @Override
    public void didSenderDirFilesCount(int aCount) {

    }

    @Override
    public void didSenderFileError() {

    }

    @Override
    public void didSenderFileSendLine(int aLineNumber) {

    }
}
