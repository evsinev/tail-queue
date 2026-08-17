package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueRetention;
import com.payneteasy.tailqueue.ITailQueueSender;
import com.payneteasy.tailqueue.TailQueueDuplicatePolicy;
import com.payneteasy.tailqueue.impl.util.FileKeys;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TailQueueDirSenderTest {

    private static final String ACTIVE_FILE = "current.json.open";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File                    dir;
    private List<String>            sentLines;
    private CountingMetricsListener metrics;

    @Before
    public void setUp() throws IOException {
        dir       = temporaryFolder.newFolder("queue");
        sentLines = new ArrayList<>();
        metrics   = new CountingMetricsListener();
    }

    @After
    public void makeDirWritableAgain() {
        //noinspection ResultOfMethodCallIgnored
        dir.setWritable(true);
    }

    @Test
    public void sendsEveryClosedFileAndNeverTouchesTheActiveOne() throws IOException {
        write("20260817-1000.json", "first\nsecond\n");
        write("20260817-1001.json", "third\n");
        write(ACTIVE_FILE        , "still being written\n");

        createDirSender(new TailQueueRetentionDeleteFile()).processDir();

        // including the last closed file: the writer never appends to a closed file
        assertThat(sentLines).containsExactly("first", "second", "third");
        assertThat(dir.list()).containsExactly(ACTIVE_FILE);
        assertThat(metrics.senderDirArchiveFile).isEqualTo(2);
        assertThat(metrics.senderDirQuarantineFile).isZero();
    }

    @Test
    public void quarantinesAFileWhichCannotBeArchived() throws IOException {
        write("20260817-1000.json", "first\n");

        TailQueueDirSender dirSender = createDirSender(aFile -> { /* retention silently does nothing */ });

        dirSender.processDir();
        dirSender.processDir();

        // sent once, then moved out of the way instead of being sent again forever
        assertThat(sentLines).containsExactly("first");
        assertThat(dir.list()).containsExactly("20260817-1000.json.failed");
        assertThat(metrics.senderDirQuarantineFile).isEqualTo(1);
        assertThat(metrics.senderDirArchiveFile).isZero();
    }

    @Test
    public void quarantineFailureStillStopsTheResendLoop() throws IOException {
        write("20260817-1000.json", "first\n");

        TailQueueDirSender dirSender = createDirSender(aFile -> {
            throw new IllegalStateException("retention is broken");
        });

        Assume.assumeTrue("cannot make the queue dir read only", dir.setWritable(false) && !dir.canWrite());

        dirSender.processDir();
        dirSender.processDir();

        // neither archived nor renamed, but remembered as sent for the process lifetime
        assertThat(sentLines).containsExactly("first");
        assertThat(dir.list()).containsExactly("20260817-1000.json");
        assertThat(metrics.senderDirQuarantineFile).isEqualTo(1);
    }

    /**
     * A file the tailer never read is sent in full even when the queue skips the files it did read.
     */
    @Test
    public void sendsAFileWhichTheTailerNeverRead() throws IOException {
        write("20260817-1000.json"          , "first\n" );
        write("20260817-1001-recovered.json", "second\n");

        TailQueueDeliveredFiles deliveredFiles = new TailQueueDeliveredFiles(TailQueueDuplicatePolicy.SKIP);

        createDirSender(new TailQueueRetentionDeleteFile(), deliveredFiles).processDir();

        assertThat(sentLines).containsExactly("first", "second");
        assertThat(metrics.senderDirSkipFile).isZero();
        assertThat(metrics.senderDirArchiveFile).isEqualTo(2);
    }

    /**
     * Retention treats a file delivered by the tailer exactly like one the dir sender itself sent,
     * so a file which cannot be archived is quarantined instead of being sent on a later cycle.
     */
    @Test
    public void quarantinesAFileDeliveredByTheTailer() throws IOException {
        write("20260817-1000.json", "first\n");

        TailQueueDeliveredFiles deliveredFiles = new TailQueueDeliveredFiles(TailQueueDuplicatePolicy.SKIP);
        deliveredFiles.add(FileKeys.fileKeyOf(new File(dir, "20260817-1000.json")));

        TailQueueDirSender dirSender = createDirSender(aFile -> { /* retention silently does nothing */ }, deliveredFiles);

        dirSender.processDir();
        dirSender.processDir();

        assertThat(sentLines).as("the tailer had already delivered it").isEmpty();
        assertThat(dir.list()).containsExactly("20260817-1000.json.failed");
        assertThat(metrics.senderDirSkipFile).isEqualTo(1);
        assertThat(metrics.senderDirQuarantineFile).isEqualTo(1);
    }

    private TailQueueDirSender createDirSender(ITailQueueRetention aRetention) {
        return createDirSender(aRetention, new TailQueueDeliveredFiles(TailQueueDuplicatePolicy.RESEND));
    }

    private TailQueueDirSender createDirSender(ITailQueueRetention aRetention, TailQueueDeliveredFiles aDeliveredFiles) {
        ITailQueueSender sender = aLine -> sentLines.add(aLine);

        return new TailQueueDirSender(
                dir
                , new TailQueueFileFilter("", ".json")
                , sender
                , aRetention
                , metrics
                , new TailQueueFileSenderImpl()
                , FileKeys.SYSTEM
                , aDeliveredFiles
        );
    }

    private void write(String aName, String aContent) throws IOException {
        Files.write(new File(dir, aName).toPath(), aContent.getBytes(UTF_8));
    }
}
