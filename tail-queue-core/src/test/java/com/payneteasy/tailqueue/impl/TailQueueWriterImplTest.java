package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.TailQueueFsyncPolicy;
import com.payneteasy.tailqueue.TailQueueRollCycle;
import com.payneteasy.tailqueue.TailQueueWriteException;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TailQueueWriterImplTest {

    private static final String ACTIVE_FILE = "current.json.open";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File                    dir;
    private MutableClock            clock;
    private CountingMetricsListener metrics;

    @Before
    public void setUp() throws IOException {
        dir     = temporaryFolder.newFolder("queue");
        clock   = new MutableClock("2026-08-17T10:00:00Z");
        metrics = new CountingMetricsListener();
    }

    @After
    public void makeDirWritableAgain() {
        //noinspection ResultOfMethodCallIgnored
        dir.setWritable(true);
    }

    @Test
    public void messagesGoToTheActiveFileOnly() {
        TailQueueWriterImpl writer = createWriter();

        writer.writeMessage("first");
        writer.writeMessage("second");

        assertThat(linesOf(ACTIVE_FILE)).containsExactly("first", "second");
        assertThat(dir.list()).containsExactly(ACTIVE_FILE);
        assertThat(metrics.writeMessageSuccess).isEqualTo(2);

        writer.close();
    }

    @Test
    public void rollsActiveFileOnBucketChange() {
        TailQueueWriterImpl writer = createWriter();

        writer.writeMessage("in first bucket");
        clock.plusMinutes(1);
        writer.writeMessage("in second bucket");

        assertThat(linesOf("20260817-1000.json")).containsExactly("in first bucket");
        assertThat(linesOf(ACTIVE_FILE)).containsExactly("in second bucket");

        writer.close();
    }

    /**
     * The clock only decides how messages are grouped, never which file is live, so a backwards
     * step may not append to a file the sender is already allowed to consume.
     */
    @Test
    public void clockGoingBackwardsNeverAppendsToAClosedFile() {
        TailQueueWriterImpl writer = createWriter();

        clock.set("2026-08-17T10:01:00Z");
        writer.writeMessage("at 10:01");

        clock.set("2026-08-17T10:00:00Z"); // NTP steps back
        writer.writeMessage("at 10:00");

        clock.set("2026-08-17T10:01:00Z"); // and forward again, into an already used bucket
        writer.writeMessage("at 10:01 again");

        clock.set("2026-08-17T10:02:00Z");
        writer.writeMessage("at 10:02");

        // the file closed before the step back was not touched again
        assertThat(linesOf("20260817-1001.json")).containsExactly("at 10:01");
        assertThat(linesOf("20260817-1000.json")).containsExactly("at 10:00");
        // the second roll into the 10:01 bucket got its own name instead of overwriting the first one
        assertThat(linesOf("20260817-1001-1.json")).containsExactly("at 10:01 again");
        assertThat(linesOf(ACTIVE_FILE)).containsExactly("at 10:02");

        writer.close();
    }

    /**
     * Publishing a file is best effort, persisting a message is not: a rename which fails may not
     * cost the message that triggered the roll.
     */
    @Test
    public void aFailedRollKeepsTheMessageInTheActiveFile() {
        TailQueueWriterImpl writer = createWriter();

        writer.writeMessage("before the failed roll");

        // renaming needs a writable dir, appending to the already open active file does not
        Assume.assumeTrue("cannot make the queue dir read only", dir.setWritable(false) && !dir.canWrite());

        clock.plusMinutes(1);
        writer.writeMessage("after the failed roll");

        assertThat(linesOf(ACTIVE_FILE)).containsExactly("before the failed roll", "after the failed roll");
        assertThat(dir.list()).containsExactly(ACTIVE_FILE);
        assertThat(metrics.writeMessageSuccess).isEqualTo(2);
        assertThat(metrics.writeMessageError).isZero();

        writer.close();
    }

    @Test
    public void repairsFramingAfterATornWrite() throws IOException {
        TailQueueWriterImpl writer = createWriter();

        writer.writeMessage("complete message");
        writer.close();

        // the process died in the middle of the next append
        appendRaw(ACTIVE_FILE, "torn mes");

        writer.writeMessage("next message");

        assertThat(linesOf(ACTIVE_FILE)).containsExactly("complete message", "torn mes", "next message");

        writer.close();
    }

    @Test
    public void recoversStaleActiveFileLeftByACrash() throws IOException {
        appendRaw(ACTIVE_FILE, "message from the previous run\ntorn mes");
        setLastModified(ACTIVE_FILE, "2026-08-17T09:58:00Z");

        TailQueueWriterImpl writer = createWriter();
        writer.writeMessage("message from this run");

        assertThat(linesOf("20260817-0958-recovered.json")).containsExactly("message from the previous run", "torn mes");
        assertThat(linesOf(ACTIVE_FILE)).containsExactly("message from this run");

        writer.close();
    }

    @Test
    public void recoveryDoesNotOverwriteAnExistingFile() throws IOException {
        appendRaw("20260817-0958-recovered.json", "recovered by an earlier run\n");
        appendRaw(ACTIVE_FILE, "message from the previous run\n");
        setLastModified(ACTIVE_FILE, "2026-08-17T09:58:00Z");

        createWriter().close();

        assertThat(linesOf("20260817-0958-recovered.json")).containsExactly("recovered by an earlier run");
        assertThat(linesOf("20260817-0958-recovered-1.json")).containsExactly("message from the previous run");
    }

    @Test
    public void emptyStaleActiveFileIsRemoved() throws IOException {
        appendRaw(ACTIVE_FILE, "");

        createWriter().close();

        assertThat(dir.list()).isEmpty();
    }

    @Test
    public void strictModeThrowsWhenTheMessageCannotBePersisted() throws IOException {
        TailQueueWriterImpl writer = createWriter(unwritableDir(), true, TailQueueFsyncPolicy.NONE);

        assertThatThrownBy(() -> writer.writeMessage("lost"))
                .isInstanceOf(TailQueueWriteException.class);

        assertThat(metrics.writeMessageError).isEqualTo(1);
        assertThat(metrics.writeMessageSuccess).isZero();
    }

    @Test
    public void lenientModeOnlyCountsAFailedWrite() throws IOException {
        TailQueueWriterImpl writer = createWriter(unwritableDir(), false, TailQueueFsyncPolicy.NONE);

        writer.writeMessage("lost");

        assertThat(metrics.writeMessageError).isEqualTo(1);
        assertThat(metrics.writeMessageSuccess).isZero();
    }

    @Test
    public void fsyncPolicyEveryMessageKeepsTheContent() {
        TailQueueWriterImpl writer = createWriter(dir, false, TailQueueFsyncPolicy.EVERY_MESSAGE);

        writer.writeMessage("forced");
        clock.plusMinutes(1);
        writer.writeMessage("forced after roll");
        writer.close();

        assertThat(linesOf("20260817-1000.json")).containsExactly("forced");
        assertThat(linesOf(ACTIVE_FILE)).containsExactly("forced after roll");
        assertThat(metrics.writeMessageSuccess).isEqualTo(2);
    }

    private TailQueueWriterImpl createWriter() {
        return createWriter(dir, false, TailQueueFsyncPolicy.NONE);
    }

    private TailQueueWriterImpl createWriter(File aDir, boolean aStrictWrites, TailQueueFsyncPolicy aFsyncPolicy) {
        return new TailQueueWriterImpl(
                aDir
                , TailQueueRollCycle.MINUTELY
                , ""
                , ".json"
                , metrics
                , aStrictWrites
                , aFsyncPolicy
                , clock
        );
    }

    /**
     * A plain file used as a queue dir: every attempt to create the active file inside it fails,
     * whatever the user the tests run as.
     */
    private File unwritableDir() throws IOException {
        return temporaryFolder.newFile("not-a-dir");
    }

    private List<String> linesOf(String aName) {
        try {
            return Files.readAllLines(new File(dir, aName).toPath(), UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + aName, e);
        }
    }

    private void appendRaw(String aName, String aContent) throws IOException {
        Files.write(new File(dir, aName).toPath(), aContent.getBytes(UTF_8), CREATE, APPEND);
    }

    private void setLastModified(String aName, String aInstant) {
        assertThat(new File(dir, aName).setLastModified(java.time.Instant.parse(aInstant).toEpochMilli())).isTrue();
    }
}
