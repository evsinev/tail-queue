package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueSender;
import com.payneteasy.tailqueue.TailQueueRollCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;

import static com.payneteasy.tailqueue.impl.util.SafeFiles.mkDirs;

public class TailQueueSenderFailsafe implements ITailQueueSender {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueSenderFailsafe.class );

    private final File             failsafeDir;
    private final int              attempts;
    private final Duration         sleepBetweenAttempts;
    private final ITailQueueSender sender;

    private final TailQueueWriterImpl failWriter;

    public TailQueueSenderFailsafe(File failsafeDir, int attempts, Duration sleepBetweenAttempts, ITailQueueSender sender) {
        this.failsafeDir          = failsafeDir;
        this.attempts             = attempts;
        this.sleepBetweenAttempts = sleepBetweenAttempts;
        this.sender               = sender;

        failWriter = new TailQueueWriterImpl(
                mkDirs(failsafeDir)
                , TailQueueRollCycle.MINUTELY.getDateFormatter()
                , ""
                , ".json"
                , new TailQueueMetricsListenerListenerNoOp()
        );
    }

    @Override
    public void sendMessage(String aLine) {
        for (int i = 0; i < attempts; i++) {
            try {
                sender.sendMessage(aLine);
                return;
            } catch (Exception e) {
                LOG.error("Cannot send line on {} / {} attempt: {}", i, attempts, aLine, e);
                try {
                    sleepBetweenAttempts();
                } catch (InterruptedException ex) {
                    LOG.error("Interrupted sleep between attempts");
                    break;
                }
            }
        }

        LOG.warn("Writing failed message to {} dir", failsafeDir.getAbsolutePath());
        failWriter.writeMessage(aLine);
    }

    private void sleepBetweenAttempts() throws InterruptedException {
        LOG.warn("Sleeping {} between attempts", sleepBetweenAttempts);
        Thread.sleep(sleepBetweenAttempts.toMillis());
    }
}
