package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueSender;
import com.payneteasy.tailqueue.TailQueueDuplicatePolicy;
import com.payneteasy.tailqueue.impl.util.FileKeys;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.payneteasy.tailqueue.TailQueueDuplicatePolicy.RESEND;
import static com.payneteasy.tailqueue.TailQueueDuplicatePolicy.SKIP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many times a message is delivered when the tailer and the dir sender see the same file.
 * <p>
 * The tests drive the tailer and the dir sender from the test thread, in the order the sender task
 * runs them, and let the sender callback do what the writer would do at that moment (roll the file,
 * drop another closed file into the directory). Every point where the tailer meets the end of the
 * file is therefore fixed by the test and not by timing.
 */
public class TailQueueDuplicatePolicyTest {

    private static final String ACTIVE    = "current.json.open";
    private static final String ROLLED    = "20260817-1000.json";
    private static final String UNRELATED = "20260817-0959.json";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File                    dir;
    private List<String>            sentLines;
    private CountingMetricsListener metrics;
    private TailQueueDeliveredFiles deliveredFiles;

    @Before
    public void setUp() throws IOException {
        dir            = temporaryFolder.newFolder("queue");
        sentLines      = new ArrayList<>();
        metrics        = new CountingMetricsListener();
        deliveredFiles = new TailQueueDeliveredFiles(RESEND);
    }

    @Test
    public void resendSendsTheWholeFileAgainAfterTheTailerDeliveredIt() throws Exception {
        usePolicy(RESEND);

        tailTheActiveFileAndRollIt();
        createDirSender().processDir();

        assertThat(sentLines).containsExactly("first", "second", "first", "second");
        assertThat(metrics.senderDirSkipFile).isZero();
        assertThat(metrics.senderDirArchiveFile).isEqualTo(1);
    }

    @Test
    public void skipArchivesTheFileTheTailerDeliveredWithoutSendingItAgain() throws Exception {
        usePolicy(SKIP);

        tailTheActiveFileAndRollIt();
        createDirSender().processDir();

        assertThat(sentLines).containsExactly("first", "second");
        assertThat(metrics.senderDirSkipFile).isEqualTo(1);
        assertThat(metrics.senderDirArchiveFile).isEqualTo(1);
        assertThat(dir.list()).as("the file is still archived, only its sending is skipped").isEmpty();
    }

    /**
     * The tailer yields on any closed file, including one which has nothing to do with the file it is
     * reading. Resuming must not deliver the beginning of the active file again.
     */
    @Test
    public void yieldingOnAnotherClosedFileDoesNotRedeliverTheLinesAlreadySent() throws Exception {
        usePolicy(SKIP);

        tailAcrossAYieldOnAnotherClosedFile();

        assertThat(sentLines).containsExactly("first", "unrelated", "second");
        assertThat(metrics.senderDirSkipFile).isEqualTo(1);
    }

    /**
     * Keeping the position is not a property of SKIP: RESEND sends the closed file again, but the
     * tailer itself must not deliver the beginning of the active file twice either.
     */
    @Test
    public void yieldingOnAnotherClosedFileDoesNotRedeliverTheLinesAlreadySentWhenResending() throws Exception {
        usePolicy(RESEND);

        tailAcrossAYieldOnAnotherClosedFile();

        assertThat(sentLines).as("the rolled file is sent again as a whole, but the tailer sent each line once")
                .containsExactly("first", "unrelated", "second", "first", "second");
        assertThat(metrics.senderDirSkipFile).isZero();
    }

    /**
     * Delivers the first line, yields because an unrelated closed file appeared, lets the dir sender
     * take that file, and only then tails the second line and lets the writer roll the file.
     */
    private void tailAcrossAYieldOnAnotherClosedFile() throws InterruptedException {
        write(ACTIVE, "first\n");

        TailQueueFileTailer tailer = createTailer(aLine -> {
            sentLines.add(aLine);

            if ("first".equals(aLine)) {
                write(UNRELATED, "unrelated\n");
            } else {
                roll();
            }
        });

        tailer.tailOneFile();

        assertThat(sentLines).containsExactly("first");

        createDirSender().processDir();

        append(ACTIVE, "second\n");
        tailer.tailOneFile();

        assertThat(sentLines).as("the tailer delivered each line once").containsExactly("first", "unrelated", "second");

        createDirSender().processDir();
    }

    /**
     * A line which could not be delivered means the file was not delivered, so it is sent in full
     * even in SKIP: duplicates for the lines which had made it through, no loss for the one which
     * had not.
     */
    @Test
    public void aFailedLineMakesTheWholeFileBeSentAgain() throws Exception {
        usePolicy(SKIP);

        write(ACTIVE, "first\nsecond\n");

        createTailer(aLine -> {
            if ("second".equals(aLine)) {
                throw new IllegalStateException("the sender is down");
            }
            sentLines.add(aLine);
        }).tailOneFile();

        assertThat(sentLines).containsExactly("first");
        assertThat(metrics.senderFileError).isEqualTo(1);

        roll();
        createDirSender().processDir();

        assertThat(sentLines).containsExactly("first", "first", "second");
        assertThat(metrics.senderDirSkipFile).isZero();
    }

    /**
     * The record of what the tailer delivered lives in memory only, so a restart re-delivers a
     * partially tailed file instead of losing the lines which had not been delivered yet.
     */
    @Test
    public void aRestartDeliversAPartiallyTailedFileAgain() throws Exception {
        usePolicy(SKIP);

        write(ACTIVE, "first\n");

        TailQueueFileTailer tailer = createTailer(aLine -> {
            sentLines.add(aLine);
            write(UNRELATED, "unrelated\n"); // makes the tailer yield at the end of the file
        });

        tailer.tailOneFile();
        tailer.close(); // the process stops here

        assertThat(sentLines).containsExactly("first");

        // the writer had appended one more message before the process died
        append(ACTIVE, "second\n");

        // the new process: its writer recovers the stale active file, its record starts empty
        moveFile(ACTIVE, "20260817-1000-recovered.json");
        usePolicy(SKIP);

        createDirSender().processDir();

        assertThat(sentLines).as("every message is delivered, the first one twice")
                .containsExactly("first", "unrelated", "first", "second");
    }

    /**
     * Delivers both lines of the active file and lets the writer publish it while the second line is
     * being sent, which is what happens in a running queue.
     */
    private void tailTheActiveFileAndRollIt() throws InterruptedException {
        write(ACTIVE, "first\nsecond\n");

        createTailer(aLine -> {
            sentLines.add(aLine);

            if ("second".equals(aLine)) {
                roll();
            }
        }).tailOneFile();
    }

    private void usePolicy(TailQueueDuplicatePolicy aPolicy) {
        deliveredFiles = new TailQueueDeliveredFiles(aPolicy);
    }

    private TailQueueFileTailer createTailer(ITailQueueSender aSender) {
        return new TailQueueFileTailer(
                dir
                , aSender
                , new TailQueueFileFilter("", ".json")
                , new TailQueueFileNames("", ".json")
                , Duration.ofMillis(1)
                , metrics
                , FileKeys.SYSTEM
                , deliveredFiles
        );
    }

    private TailQueueDirSender createDirSender() {
        return new TailQueueDirSender(
                dir
                , new TailQueueFileFilter("", ".json")
                , aLine -> sentLines.add(aLine)
                , new TailQueueRetentionDeleteFile()
                , metrics
                , new TailQueueFileSenderImpl()
                , FileKeys.SYSTEM
                , deliveredFiles
        );
    }

    private void roll() {
        moveFile(ACTIVE, ROLLED);
    }

    private void moveFile(String aFrom, String aTo) {
        try {
            Files.move(new File(dir, aFrom).toPath(), new File(dir, aTo).toPath());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot move " + aFrom + " to " + aTo, e);
        }
    }

    private void write(String aName, String aContent) {
        try {
            Files.write(new File(dir, aName).toPath(), aContent.getBytes(UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + aName, e);
        }
    }

    private void append(String aName, String aContent) {
        try {
            Files.write(new File(dir, aName).toPath(), aContent.getBytes(UTF_8), APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to " + aName, e);
        }
    }
}
