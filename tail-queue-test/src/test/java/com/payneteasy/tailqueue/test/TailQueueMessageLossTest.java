package com.payneteasy.tailqueue.test;

import com.payneteasy.tailqueue.ITailQueue;
import com.payneteasy.tailqueue.ITailQueueSender;
import com.payneteasy.tailqueue.ITailQueueWriter;
import com.payneteasy.tailqueue.TailQueueBuilder;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end checks for the "no message is lost" property.
 */
public class TailQueueMessageLossTest {

    private static final Logger LOG = LoggerFactory.getLogger(TailQueueMessageLossTest.class);

    private static final String   ACTIVE_FILE = "current.json.open";
    private static final Duration WAIT        = Duration.ofMillis(50);
    private static final Duration TIMEOUT     = Duration.ofSeconds(30);

    private File         dir;
    private TestClock    clock;
    private Set<String>  delivered;

    @Before
    public void setUp() {
        dir       = new File("target/message-loss/" + System.currentTimeMillis());
        clock     = new TestClock(Instant.parse("2026-08-17T10:00:00Z"));
        delivered = ConcurrentHashMap.newKeySet();
    }

    /**
     * The sender must never delete or archive the file the writer is appending to, whatever
     * happens in parallel, and every written message must be delivered at least once.
     */
    @Test
    public void liveFileIsNeverDeletedWhileWriting() throws InterruptedException {
        ITailQueue queue = createQueue();
        queue.startQueueSender();

        ITailQueueWriter writer = queue.getWriter();

        int count = 200;
        for (int i = 0; i < count; i++) {
            writer.writeMessage("message-" + i);

            if (i % 20 == 0) {
                clock.plusMinutes(1); // force a roll while the sender is working on the dir
            }

            assertThat(new File(dir, ACTIVE_FILE))
                    .as("the active file must stay on disk while the writer uses it")
                    .exists();

            Thread.sleep(5);
        }

        awaitDelivered(count);

        queue.shutdownQueueSender();

        for (int i = 0; i < count; i++) {
            assertThat(delivered).contains("message-" + i);
        }
    }

    /**
     * A stale active file left by a crashed process is published on startup and delivered.
     */
    @Test
    public void staleActiveFileIsDeliveredAfterRestart() throws IOException, InterruptedException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Files.write(new File(dir, ACTIVE_FILE).toPath(), "before-crash-1\nbefore-crash-2\n".getBytes(UTF_8));

        ITailQueue queue = createQueue();
        queue.startQueueSender();

        try {
            queue.getWriter().writeMessage("after-restart");

            awaitDelivered(3);

            assertThat(delivered).contains("before-crash-1", "before-crash-2", "after-restart");
        } finally {
            queue.shutdownQueueSender();
        }
    }

    private ITailQueue createQueue() {
        ITailQueueSender sender = aLine -> {
            LOG.debug("Delivered {}", aLine);
            delivered.add(aLine);
        };

        return new TailQueueBuilder()
                .dir(dir)
                .sender(sender)
                .clock(clock)
                .liveWaitDuration(WAIT)
                .dirListWaitDuration(WAIT)
                .build();
    }

    private void awaitDelivered(int aCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();

        while (delivered.size() < aCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private static class TestClock extends Clock {

        private volatile Instant now;

        TestClock(Instant aNow) {
            now = aNow;
        }

        void plusMinutes(long aMinutes) {
            now = now.plus(Duration.ofMinutes(aMinutes));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId aZone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
