package com.payneteasy.tailqueue;

import com.payneteasy.tailqueue.impl.*;

import java.io.File;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

public class TailQueueBuilder {

    private ITailQueueSender    sender;
    private File                dir;

    private TailQueueRollCycle  rollCycle           = TailQueueRollCycle.MINUTELY;
    private String              filePrefix          = "";
    private String              fileSuffix          = ".json";
    private ITailQueueStat      statListener        = new TailQueueStatListenerNoOp();
    private ITailQueueRetention retention           = new TailQueueRetentionDeleteFile();
    private Duration            liveWaitDuration    = Duration.ofMillis(500);
    private Duration            dirListWaitDuration = Duration.ofMillis(500);

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

    public TailQueueBuilder statListener(ITailQueueStat statListener) {
        this.statListener = statListener;
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

    public ITailQueue build() {
        requireNonNull(sender, "Sender is null");
        requireNonNull(dir, "Dir is null");

        if(!dir.exists()) {
            if(!dir.mkdirs()) {
                throw new IllegalStateException("Cannot create dir " + dir.getAbsolutePath());
            }
        }

        ITailQueueWriter writer = new TailQueueWriterImpl(
                dir
                , rollCycle.getDateFormatter()
                , filePrefix
                , fileSuffix
                , statListener
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
        );

        TailQueueFileTailer fileTailer = new TailQueueFileTailer(
                dir
                , sender
                , fileFilter
                , liveWaitDuration
        );

        return new TailQueueSenderTask(
                dir
                , dirSender
                , fileTailer
                , dirListWaitDuration
        );
    }
}
