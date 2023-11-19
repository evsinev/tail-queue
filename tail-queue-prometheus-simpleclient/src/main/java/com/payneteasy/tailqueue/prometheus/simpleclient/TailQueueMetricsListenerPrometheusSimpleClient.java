package com.payneteasy.tailqueue.prometheus.simpleclient;

import com.payneteasy.tailqueue.ITailQueueMetricsListener;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class TailQueueMetricsListenerPrometheusSimpleClient implements ITailQueueMetricsListener {

    private final String        queueName;
    private final Counter.Child writeMessageSuccess;
    private final Counter.Child writeMessageError;
    private final Counter.Child senderTaskErrorProcessingDir;
    private final Counter.Child senderDirArchiveFile;
    private final Gauge.Child   senderDirSendFileCurrent;
    private final Gauge.Child   senderDirSendFileCount;
    private final Gauge.Child   senderDirFilesCount;
    private final Counter.Child senderFileError;
    private final Gauge.Child   senderFileSendLine;

    @Override
    public void didWriteMessageSuccess() {
        writeMessageSuccess.inc();
    }

    @Override
    public void didWriteMessageError() {
        writeMessageError.inc();
    }

    @Override
    public void didSenderTaskErrorProcessingDir() {
        senderTaskErrorProcessingDir.inc();
    }

    @Override
    public void didSenderDirArchiveFile() {
        senderDirArchiveFile.inc();
    }

    @Override
    public void didSenderDirSendFile(int current, int count) {
        senderDirSendFileCurrent.set(current);
        senderDirSendFileCount.set(current);
    }

    @Override
    public void didSenderDirFilesCount(int aCount) {
        senderDirFilesCount.set(aCount);
    }

    @Override
    public void didSenderFileError() {
        senderFileError.inc();
    }

    @Override
    public void didSenderFileSendLine(int aLineNumber) {
        senderFileSendLine.set(aLineNumber);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("TailQueueMetricsListenerPrometheusSimpleClient{");
        sb.append("queueName='").append(queueName).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
