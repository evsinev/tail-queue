package com.payneteasy.tailqueue;

public interface ITailQueueMetricsListener {

    void didWriteMessageSuccess();

    void didWriteMessageError();

    void didSenderTaskErrorProcessingDir();

    void didSenderDirArchiveFile();

    /**
     * A file was sent but could not be archived, so it was moved out of the way instead of being
     * sent again on the next cycle. Needs manual handling.
     */
    default void didSenderDirQuarantineFile() {
    }

    void didSenderDirSendFile(int current, int count);

    void didSenderDirFilesCount(int aCount);

    void didSenderFileError();

    void didSenderFileSendLine(int aLineNumber);

    void didSenderDirSendLineSuccess();

    void didSenderFileSendLineSuccess();
}
