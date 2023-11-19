package com.payneteasy.tailqueue;

public interface ITailQueueMetricsListener {

    void didWriteMessageSuccess();

    void didWriteMessageError();

    void didSenderTaskErrorProcessingDir();

    void didSenderDirArchiveFile();

    void didSenderDirSendFile(int current, int count);

    void didSenderDirFilesCount(int aCount);

    void didSenderFileError();

    void didSenderFileSendLine(int aLineNumber);
}
