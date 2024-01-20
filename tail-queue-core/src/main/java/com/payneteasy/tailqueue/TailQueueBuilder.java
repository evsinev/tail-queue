package com.payneteasy.tailqueue;

import com.payneteasy.tailqueue.impl.*;

import java.io.File;
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

    public ITailQueue build() {
        requireNonNull(sender, "Sender is null");
        requireNonNull(dir, "Dir is null");

        mkDirs(dir);

        ITailQueueWriter writer = new TailQueueWriterImpl(
                dir
                , rollCycle.getDateFormatter()
                , filePrefix
                , fileSuffix
                , metricsListener
        );

        TailQueueSenderTask senderTask = createSenderTask();

        return new TailQueueImpl(
                writer
                , senderTask
        );
    }

    private TailQueueSenderTask createSenderTask() {

        TailQueueFileFilter fileFilter = new TailQueueFileFilter(filePrefix, fileSuffix);

        TailQueueDirSender dirSender = new TailQueueDirSender(
                dir
                , fileFilter
                , sender
                , retention
                , metricsListener
                , fileSender
        );

        TailQueueFileTailer fileTailer = new TailQueueFileTailer(
                dir
                , sender
                , fileFilter
                , liveWaitDuration
                , metricsListener
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
