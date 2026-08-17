package com.payneteasy.tailqueue;

import com.payneteasy.tailqueue.impl.*;
import com.payneteasy.tailqueue.impl.util.FileKeys;
import com.payneteasy.tailqueue.impl.util.IFileKeyResolver;

import java.io.File;
import java.time.Clock;
import java.time.Duration;

import static com.payneteasy.tailqueue.impl.util.SafeFiles.mkDirs;
import static java.util.Objects.requireNonNull;

public class TailQueueBuilder {

    private ITailQueueSender    sender;
    private File                dir;

    private TailQueueRollCycle        rollCycle           = TailQueueRollCycle.MINUTELY;
    private String                    filePrefix          = "";
    private String                    fileSuffix          = ".json";
    private ITailQueueMetricsListener metricsListener     = new TailQueueMetricsListenerListenerNoOp();
    private ITailQueueRetention       retention           = new TailQueueRetentionDeleteFile();
    private Duration                  liveWaitDuration    = Duration.ofMillis(500);
    private Duration                  dirListWaitDuration = Duration.ofMillis(500);
    private ITailQueueFileSender      fileSender          = new TailQueueFileSenderImpl();
    private boolean                   strictWrites        = false;
    private TailQueueFsyncPolicy      fsyncPolicy         = TailQueueFsyncPolicy.NONE;
    private TailQueueDuplicatePolicy  duplicatePolicy     = TailQueueDuplicatePolicy.RESEND;
    private Clock                     clock               = Clock.systemUTC();
    private IFileKeyResolver          fileKeys            = FileKeys.SYSTEM;

    public TailQueueBuilder sender(ITailQueueSender sender) {
        this.sender = sender;
        return this;
    }

    public TailQueueBuilder dir(File dir) {
        this.dir = dir;
        return this;
    }

    public TailQueueBuilder rollCycle(TailQueueRollCycle rollCycle) {
        this.rollCycle = rollCycle;
        return this;
    }

    public TailQueueBuilder filePrefix(String filePrefix) {
        this.filePrefix = filePrefix;
        return this;
    }

    public TailQueueBuilder fileSuffix(String fileSuffix) {
        this.fileSuffix = fileSuffix;
        return this;
    }

    public TailQueueBuilder metricsListener(ITailQueueMetricsListener statListener) {
        this.metricsListener = statListener;
        return this;
    }

    public TailQueueBuilder retention(ITailQueueRetention retention) {
        this.retention = retention;
        return this;
    }

    public TailQueueBuilder liveWaitDuration(Duration liveWaitDuration) {
        this.liveWaitDuration = liveWaitDuration;
        return this;
    }

    public TailQueueBuilder dirListWaitDuration(Duration dirListWaitDuration) {
        this.dirListWaitDuration = dirListWaitDuration;
        return this;
    }

    public TailQueueBuilder fileSender(ITailQueueFileSender fileSender) {
        this.fileSender = fileSender;
        return this;
    }

    /**
     * @param strictWrites if true, {@link ITailQueueWriter#writeMessage(String)} throws a
     *                     {@link TailQueueWriteException} instead of only logging and counting
     *                     a failure to persist the message. Default is false.
     */
    public TailQueueBuilder strictWrites(boolean strictWrites) {
        this.strictWrites = strictWrites;
        return this;
    }

    /**
     * @param fsyncPolicy {@link TailQueueFsyncPolicy#EVERY_MESSAGE} makes a successful
     *                    {@code writeMessage} guarantee the message survives a power loss.
     *                    Default is {@link TailQueueFsyncPolicy#NONE}.
     */
    public TailQueueBuilder fsyncPolicy(TailQueueFsyncPolicy fsyncPolicy) {
        this.fsyncPolicy = fsyncPolicy;
        return this;
    }

    /**
     * @param duplicatePolicy {@link TailQueueDuplicatePolicy#SKIP} stops the dir sender from sending
     *                        a file the tailer has already delivered in full, which makes delivery
     *                        one copy per message in the normal case. Default is
     *                        {@link TailQueueDuplicatePolicy#RESEND}, today's two copies.
     *                        <p>
     *                        The {@code sender_dir_skip_file} metric counts the files skipped, so a
     *                        flat zero on a busy queue means the mode is not taking effect.
     */
    public TailQueueBuilder duplicatePolicy(TailQueueDuplicatePolicy duplicatePolicy) {
        this.duplicatePolicy = duplicatePolicy;
        return this;
    }

    /**
     * @param clock source of time for the roll cycle buckets. For tests.
     */
    public TailQueueBuilder clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * @param fileKeys how the sender identifies a file across a rename. For tests: production uses
     *                 {@link FileKeys#SYSTEM}.
     */
    TailQueueBuilder fileKeys(IFileKeyResolver fileKeys) {
        this.fileKeys = fileKeys;
        return this;
    }

    public ITailQueue build() {
        requireNonNull(sender, "Sender is null");
        requireNonNull(dir, "Dir is null");

        mkDirs(dir);

        checkFileKeysAreSupported();

        // the writer recovers a stale active file left by a crash, so it must be created
        // before the sender task is able to process the directory
        TailQueueWriterImpl writer = new TailQueueWriterImpl(
                dir
                , rollCycle
                , filePrefix
                , fileSuffix
                , metricsListener
                , strictWrites
                , fsyncPolicy
                , clock
        );

        TailQueueSenderTask senderTask = createSenderTask();

        return new TailQueueImpl(
                writer
                , senderTask
        );
    }

    /**
     * The sender identifies a file across the rename by which the writer publishes it: the tailer
     * needs it to tell its own rolled file from an unrelated closed file, and {@code SKIP} needs it
     * to recognize a file it has already delivered.
     * <p>
     * Without file keys the tailer refuses to open the active file at all, so nothing would be
     * delivered live and every message would wait for its file to be rolled. That is a silent
     * latency regression rather than a loss, and the queue refuses to start instead of hiding it.
     */
    private void checkFileKeysAreSupported() {
        if (fileKeys.fileKeyOf(dir) != null) {
            return;
        }

        throw new IllegalStateException("The filesystem of " + dir.getAbsolutePath()
                + " does not expose file keys, which the sender needs to identify a file across a"
                + " rename. Duplicate policy " + duplicatePolicy + " cannot be honored on it.");
    }

    private TailQueueSenderTask createSenderTask() {

        TailQueueFileNames      fileNames      = new TailQueueFileNames(filePrefix, fileSuffix);
        TailQueueFileFilter     fileFilter     = new TailQueueFileFilter(fileNames);
        TailQueueDeliveredFiles deliveredFiles = new TailQueueDeliveredFiles(duplicatePolicy);

        TailQueueDirSender dirSender = new TailQueueDirSender(
                dir
                , fileFilter
                , sender
                , retention
                , metricsListener
                , fileSender
                , fileKeys
                , deliveredFiles
        );

        TailQueueFileTailer fileTailer = new TailQueueFileTailer(
                dir
                , sender
                , fileFilter
                , fileNames
                , liveWaitDuration
                , metricsListener
                , fileKeys
                , deliveredFiles
        );

        return new TailQueueSenderTask(
                dir
                , dirSender
                , fileTailer
                , dirListWaitDuration
                , metricsListener
        );
    }
}
