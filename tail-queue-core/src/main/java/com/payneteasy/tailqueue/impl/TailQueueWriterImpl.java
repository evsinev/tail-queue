package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueMetricsListener;
import com.payneteasy.tailqueue.ITailQueueWriter;
import com.payneteasy.tailqueue.TailQueueFsyncPolicy;
import com.payneteasy.tailqueue.TailQueueRollCycle;
import com.payneteasy.tailqueue.TailQueueWriteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;

import static com.payneteasy.tailqueue.TailQueueFsyncPolicy.EVERY_MESSAGE;
import static com.payneteasy.tailqueue.impl.util.SafeFiles.moveFile;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Appends messages to a single active file and publishes them by renaming that file
 * to its time bucket name. See {@link TailQueueFileNames} for the naming protocol.
 * <p>
 * A file with a bucket name never receives appends, so the sender may consume it without
 * racing with the writer, whatever the system clock does.
 * <p>
 * Writing goes through a {@link FileOutputStream} and not through a {@link java.nio.channels.FileChannel}
 * on purpose: a channel is interruptible, so a message written from a thread whose interrupt flag is
 * set would be lost. Messages must still reach the disk while the application is shutting down.
 */
public class TailQueueWriterImpl implements ITailQueueWriter, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(TailQueueWriterImpl.class);

    private static final byte NEW_LINE = 0x0A;
    private static final byte CARRIAGE_RETURN = 0x0D;

    private final File                      dir;
    private final TailQueueRollCycle        rollCycle;
    private final TailQueueFileNames        fileNames;
    private final ITailQueueMetricsListener tailQueueStat;
    private final boolean                   strictWrites;
    private final TailQueueFsyncPolicy      fsyncPolicy;
    private final Clock                     clock;

    private FileOutputStream out;
    private String           currentBucket;

    public TailQueueWriterImpl(File dir, TailQueueRollCycle rollCycle, String filePrefix, String fileSuffix, ITailQueueMetricsListener tailQueueStat) {
        this(dir, rollCycle, filePrefix, fileSuffix, tailQueueStat, false, TailQueueFsyncPolicy.NONE, Clock.systemUTC());
    }

    public TailQueueWriterImpl(
              File                      dir
            , TailQueueRollCycle        rollCycle
            , String                    filePrefix
            , String                    fileSuffix
            , ITailQueueMetricsListener tailQueueStat
            , boolean                   strictWrites
            , TailQueueFsyncPolicy      fsyncPolicy
            , Clock                     clock
    ) {
        this.dir           = dir;
        this.rollCycle     = rollCycle;
        this.fileNames     = new TailQueueFileNames(filePrefix, fileSuffix);
        this.tailQueueStat = tailQueueStat;
        this.strictWrites  = strictWrites;
        this.fsyncPolicy   = fsyncPolicy;
        this.clock         = clock;

        recoverStaleActiveFile();
    }

    @Override
    public synchronized void writeMessage(String aMessage) {
        if (aMessage == null || aMessage.isEmpty()) {
            return;
        }

        byte[] bytes  = encodeToBytes(aMessage);
        String bucket = rollCycle.formatBucket(clock.instant());

        if (LOG.isTraceEnabled()) {
            LOG.trace("Writing to {}: {}", fileNames.activeFile(dir).getAbsolutePath(), aMessage);
        }

        try {
            FileOutputStream active = ensureOpen(bucket);

            active.write(bytes);

            if (fsyncPolicy == EVERY_MESSAGE) {
                active.getFD().sync();
            }

            tailQueueStat.didWriteMessageSuccess();

        } catch (Exception e) {
            // the next message reopens the active file and repairs the framing if this write was torn
            closeQuietly();

            tailQueueStat.didWriteMessageError();
            LOG.error("Cannot write to file {} : {}", fileNames.activeFile(dir).getAbsolutePath(), aMessage, e);

            if (strictWrites) {
                throw new TailQueueWriteException("Cannot write message to " + fileNames.activeFile(dir).getAbsolutePath(), e);
            }
        }
    }

    /**
     * Closes the active file without rolling it. The messages already written stay in the
     * active file and are rolled by the next writer started on this directory.
     */
    @Override
    public synchronized void close() {
        if (out == null) {
            return;
        }

        try {
            if (fsyncPolicy == EVERY_MESSAGE) {
                out.getFD().sync();
            }
            out.close();
        } catch (IOException e) {
            LOG.error("Cannot close file {}", fileNames.activeFile(dir).getAbsolutePath(), e);
        } finally {
            out           = null;
            currentBucket = null;
        }
    }

    private FileOutputStream ensureOpen(String aBucket) throws IOException {
        if (out != null) {

            if (aBucket.equals(currentBucket)) {
                return out;
            }

            roll();
        }

        return openActiveFile(aBucket);
    }

    /**
     * Publishes the active file: forces it if needed and renames it to its bucket name.
     */
    private void roll() throws IOException {
        String bucket = currentBucket;

        if (fsyncPolicy == EVERY_MESSAGE) {
            out.getFD().sync();
        }
        out.close();

        out           = null;
        currentBucket = null;

        File active = fileNames.activeFile(dir);
        if (!active.isFile()) {
            return;
        }

        File closed = fileNames.freeBucketFile(dir, bucket);
        moveFile(active, closed);

        LOG.debug("Rolled active file to {}", closed.getAbsolutePath());
    }

    private FileOutputStream openActiveFile(String aBucket) throws IOException {
        File active = fileNames.activeFile(dir);

        boolean needsNewLine = !endsWithNewLine(active);

        FileOutputStream stream = new FileOutputStream(active, true);

        if (needsNewLine) {
            LOG.warn("File {} does not end with a new line, repairing framing", active.getAbsolutePath());
            try {
                stream.write(NEW_LINE);
            } catch (IOException e) {
                stream.close();
                throw e;
            }
        }

        out           = stream;
        currentBucket = aBucket;

        return stream;
    }

    /**
     * A stale active file means the previous run crashed. Its messages are published under the
     * bucket of its last modification, so they re-enter normal delivery.
     */
    private void recoverStaleActiveFile() {
        File active = fileNames.activeFile(dir);

        if (!active.isFile()) {
            return;
        }

        try {
            if (active.length() == 0) {
                Files.deleteIfExists(active.toPath());
                return;
            }

            String bucket    = rollCycle.formatBucket(Instant.ofEpochMilli(active.lastModified()));
            File   recovered = fileNames.freeRecoveredFile(dir, bucket);

            moveFile(active, recovered);

            LOG.warn("Recovered stale active file {} to {}", active.getAbsolutePath(), recovered.getAbsolutePath());

        } catch (Exception e) {
            // not fatal: the writer appends to the stale file and rolls it together with the new messages
            LOG.error("Cannot recover stale active file {}", active.getAbsolutePath(), e);
        }
    }

    private void closeQuietly() {
        if (out == null) {
            return;
        }

        try {
            out.close();
        } catch (IOException e) {
            LOG.debug("Cannot close file {}", fileNames.activeFile(dir).getAbsolutePath(), e);
        } finally {
            out           = null;
            currentBucket = null;
        }
    }

    private static boolean endsWithNewLine(File aFile) throws IOException {
        long length = aFile.length();

        if (!aFile.isFile() || length == 0) {
            return true;
        }

        try (RandomAccessFile in = new RandomAccessFile(aFile, "r")) {
            in.seek(length - 1);
            return in.read() == NEW_LINE;
        }
    }

    private static byte[] encodeToBytes(String aMessage) {
        byte[] orig = aMessage.getBytes(UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(orig.length + 1);

        for (byte b : orig) {

            if (b == NEW_LINE) { // remove \n
                continue;
            }

            if (b == CARRIAGE_RETURN) { // remove \r
                continue;
            }

            out.write(b);
        }

        // write new line \n
        out.write(NEW_LINE);

        return out.toByteArray();
    }

}
