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

    /**
     * A closed file was archived without being sent, because the tailer had already delivered every
     * line of it. Only happens with {@link TailQueueDuplicatePolicy#SKIP}, where a flat zero while
     * the queue is busy means the mode is not taking effect.
     */
    default void didSenderDirSkipFile() {
    }

    void didSenderDirFilesCount(int aCount);

    void didSenderFileError();

    void didSenderFileSendLine(int aLineNumber);

    void didSenderDirSendLineSuccess();

    void didSenderFileSendLineSuccess();
}
