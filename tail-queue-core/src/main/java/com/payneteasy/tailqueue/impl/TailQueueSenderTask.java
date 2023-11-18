package com.payneteasy.tailqueue.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;

import static java.lang.Thread.currentThread;

public class TailQueueSenderTask implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(TailQueueSenderTask.class);

    private final File                dir;
    private final TailQueueDirSender  dirSender;
    private final TailQueueFileTailer fileTailer;
    private final Duration            sleepDuration;

    public TailQueueSenderTask(File dir, TailQueueDirSender dirSender, TailQueueFileTailer fileTailer, Duration sleepDuration) {
        this.dir           = dir;
        this.dirSender     = dirSender;
        this.fileTailer    = fileTailer;
        this.sleepDuration = sleepDuration;
    }

    @Override
    public void run() {
        currentThread().setName("sender-" + dir.getParent() + "-" + dir.getName());

        while (!currentThread().isInterrupted()) {
            try {
                dirSender.processDir();

                fileTailer.tailOneFile();

                LOG.trace("Sleeping {} between sending dir ...", sleepDuration);
                //noinspection BusyWait
                Thread.sleep(sleepDuration.toMillis());

            } catch (InterruptedException e) {
                LOG.warn("Sender cycle interrupted");
                break;

            } catch (Exception e) {
                LOG.error("Cannot process dir {}", dir, e);
            }
        }
        LOG.debug("Exited from sender task");
    }
}
