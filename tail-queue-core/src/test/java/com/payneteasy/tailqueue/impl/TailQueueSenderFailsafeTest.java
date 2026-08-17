package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueSender;
import com.payneteasy.tailqueue.TailQueueWriteException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TailQueueSenderFailsafeTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final ITailQueueSender BROKEN_SENDER = aLine -> {
        throw new IllegalStateException("cannot send " + aLine);
    };

    private File dir;
    private File failsafeDir;

    @Before
    public void setUp() throws IOException {
        dir         = temporaryFolder.newFolder("queue");
        failsafeDir = new File(temporaryFolder.getRoot(), "failsafe");
    }

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    /**
     * A message which can neither be sent nor written to the failsafe dir must not be treated as
     * delivered, so the file it came from stays in the queue dir and is retried on a later cycle.
     */
    @Test
    public void unwritableFailsafeDirLeavesTheSourceFileInTheQueue() throws IOException {
        TailQueueSenderFailsafe failsafe = createFailsafe(1);

        breakFailsafeDir();

        Files.write(new File(dir, "20260817-1000.json").toPath(), "first\n".getBytes(UTF_8));

        TailQueueDirSender dirSender = new TailQueueDirSender(
                dir
                , new TailQueueFileFilter("", ".json")
                , failsafe
                , new TailQueueRetentionDeleteFile()
                , new TailQueueMetricsListenerListenerNoOp()
                , new TailQueueFileSenderImpl()
        );

        assertThatThrownBy(dirSender::processDir)
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(IOException.class);

        assertThat(dir.list()).containsExactly("20260817-1000.json");
    }

    /**
     * A dead letter must be visible to the ops tooling immediately, so it may not stay in the
     * active file until the writer happens to roll it.
     */
    @Test
    public void aDivertedMessageIsPublishedAsAClosedFile() throws IOException {
        createFailsafe(1).sendMessage("dead letter");

        assertThat(failsafeDir.list())
                .hasSize(1)
                .allMatch(aName -> aName.matches("\\d{8}-\\d{4}\\.json"), "closed bucket file");

        assertThat(failsafeLines()).containsExactly("dead letter");
    }

    @Test
    public void failsafeWriteFailurePropagates() throws IOException {
        TailQueueSenderFailsafe failsafe = createFailsafe(1);

        breakFailsafeDir();

        assertThatThrownBy(() -> failsafe.sendMessage("first"))
                .isInstanceOf(TailQueueWriteException.class);
    }

    /**
     * Shutting the sender down must not lose the message in flight, and must not swallow
     * the shutdown request either.
     */
    @Test
    public void interruptDuringRetriesKeepsTheInterruptFlagAndWritesTheMessage() throws IOException {
        TailQueueSenderFailsafe failsafe = createFailsafe(3);

        Thread.currentThread().interrupt();

        failsafe.sendMessage("interrupted message");

        assertThat(Thread.currentThread().isInterrupted())
                .as("the shutdown request must survive the failsafe write")
                .isTrue();

        assertThat(failsafeLines()).containsExactly("interrupted message");
    }

    private TailQueueSenderFailsafe createFailsafe(int aAttempts) {
        return new TailQueueSenderFailsafe(failsafeDir, aAttempts, Duration.ofMillis(1), BROKEN_SENDER);
    }

    /**
     * Replaces the already created failsafe dir with a plain file, so that every write into it
     * fails whatever the user the tests run as.
     */
    private void breakFailsafeDir() throws IOException {
        Files.delete(failsafeDir.toPath());
        Files.createFile(failsafeDir.toPath());
    }

    private List<String> failsafeLines() throws IOException {
        File[] files = failsafeDir.listFiles();
        assertThat(files).hasSize(1);

        return Files.readAllLines(files[0].toPath(), UTF_8);
    }
}
