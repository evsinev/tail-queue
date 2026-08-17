package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueMetricsListener;
import com.payneteasy.tailqueue.ITailQueueSender;
import com.payneteasy.tailqueue.impl.util.IFileKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newInputStream;

/**
 * Tails the active file the writer is appending to. The file is only ever read: it is
 * published by the writer itself, and only then sent and archived by the dir sender.
 * <p>
 * The reader is kept between calls, for as long as the file it reads exists, so that a line is
 * never delivered twice because tailing yielded and resumed. When the writer rolls that file, the
 * tailer drains it to its end of file and records it as delivered, which lets the dir sender in
 * {@link com.payneteasy.tailqueue.TailQueueDuplicatePolicy#SKIP} archive it without sending its
 * lines again. The record is written before the tailer yields, because the dir sender runs first in
 * the sender cycle and would otherwise send the file before hearing about it.
 */
public class TailQueueFileTailer implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueFileTailer.class );

    private final File                      dir;
    private final ITailQueueSender          sender;
    private final TailQueueFileFilter       fileFilter;
    private final TailQueueFileNames        fileNames;
    private final Duration                  lineDuration;
    private final ITailQueueMetricsListener  metricsListener;
    private final IFileKeyResolver          fileKeys;
    private final TailQueueDeliveredFiles   deliveredFiles;

    private TailQueueStrictLineReader reader;
    private Object                    readerFileKey;
    private String                    readerFileName;

    public TailQueueFileTailer(
              File                      dir
            , ITailQueueSender          sender
            , TailQueueFileFilter       fileFilter
            , TailQueueFileNames        fileNames
            , Duration                  lineDuration
            , ITailQueueMetricsListener metricsListener
            , IFileKeyResolver          fileKeys
            , TailQueueDeliveredFiles   deliveredFiles
    ) {
        this.dir             = dir;
        this.sender          = sender;
        this.fileFilter      = fileFilter;
        this.fileNames       = fileNames;
        this.lineDuration    = lineDuration;
        this.metricsListener = metricsListener;
        this.fileKeys        = fileKeys;
        this.deliveredFiles  = deliveredFiles;
    }

    public void tailOneFile() throws InterruptedException {
        if (reader == null && !openActiveFile()) {
            return;
        }

        tailFile();
    }

    /**
     * Releases the file descriptor and forgets the position, so the file is delivered from its
     * first line again. Called when the sender stops and when delivery of a line failed.
     */
    @Override
    public void close() {
        if (reader == null) {
            return;
        }

        try {
            reader.close();
        } catch (IOException e) {
            LOG.debug("Cannot close the tailed file {}", readerFileName, e);
        } finally {
            reader         = null;
            readerFileKey  = null;
            readerFileName = null;
        }
    }

    private boolean openActiveFile() {
        File   active = fileNames.activeFile(dir);
        Object before = fileKeys.fileKeyOf(active);

        if (before == null) {
            return false;
        }

        TailQueueStrictLineReader in;
        try {
            in = new TailQueueStrictLineReader(new InputStreamReader(newInputStream(active.toPath()), UTF_8));
        } catch (IOException e) {
            LOG.error("Cannot open file {}", active.getAbsolutePath(), e);
            metricsListener.didSenderFileError();
            return false;
        }

        // the writer may have rolled the file between the two calls: the reader would then hold one
        // file while its key names another one, and recording that key as delivered would make the
        // dir sender skip a file nobody has read
        if (!before.equals(fileKeys.fileKeyOf(active))) {
            LOG.debug("File {} was rolled while being opened, tailing it on a later cycle", active.getAbsolutePath());
            closeQuietly(in);
            return false;
        }

        reader         = in;
        readerFileKey  = before;
        readerFileName = active.getName();

        LOG.debug("Start tailing file {} ...", active.getAbsolutePath());

        return true;
    }

    private void tailFile() throws InterruptedException {
        try {
            while (!Thread.currentThread().isInterrupted()) {

                String line = reader.readLine();

                if (line != null) {
                    sendLine(line);
                    continue;
                }

                if (!reader.isEndOfFile()) {
                    // not the end of the file: either an empty line, which the tailer does not
                    // deliver, or a line longer than what the reader returns in one call. Both made
                    // progress, so reading on cannot spin
                    continue;
                }

                if (wasRolled()) {
                    finishRolledFile();
                    return;
                }

                if (hasClosedFile()) {
                    // the dir sender has work to do; the reader keeps its position in the active file
                    LOG.debug("Found a closed file. Exiting ...");
                    return;
                }

                sleepForNewLine();
            }
        } catch (InterruptedException e) {
            LOG.warn("Tailing file interrupted {}", readerFileName);
            throw e;
        } catch (Exception e) {
            // the position is dropped on purpose: the dir sender sends the whole file, which costs
            // duplicates for the lines already delivered and loses none of the ones which failed
            LOG.error("Cannot process file {}", readerFileName, e);
            metricsListener.didSenderFileError();
            close();
        }
    }

    private void sendLine(String aLine) {
        LOG.debug("Send line {}:{} ...", readerFileName, reader.getLineNumber());

        sender.sendMessage(aLine);

        metricsListener.didSenderFileSendLine(reader.getLineNumber());
        metricsListener.didSenderFileSendLineSuccess();
    }

    /**
     * @return true if the file the reader holds is no longer the active file, which means the writer
     *         has published it under its bucket name
     */
    private boolean wasRolled() {
        Object activeKey = fileKeys.fileKeyOf(fileNames.activeFile(dir));

        return activeKey == null || !activeKey.equals(readerFileKey);
    }

    /**
     * The writer appends and only then renames, so lines may have arrived after the last read: the
     * file is drained to its real end before it counts as delivered.
     * <p>
     * Whatever stops the drain short of that - an interrupt, or a last line without its terminating
     * new line, which this reader keeps in its buffer and would never hand out - leaves the file
     * unrecorded, so the dir sender sends it in full. That costs duplicates for the lines already
     * delivered and loses none.
     */
    private void finishRolledFile() throws IOException {
        String line;
        while ((line = reader.readLine()) != null || !reader.isEndOfFile()) {

            if (Thread.currentThread().isInterrupted()) {
                LOG.warn("Interrupted while draining the rolled file {}, it will be sent as a whole", readerFileName);
                close();
                return;
            }

            if (line != null) {
                sendLine(line);
            }
        }

        if (reader.hasPartialLine()) {
            LOG.warn("Rolled file {} does not end with a new line, sending it as a whole", readerFileName);
            close();
            return;
        }

        deliveredFiles.add(readerFileKey);

        LOG.debug("File {} was rolled, delivered {} lines of it", readerFileName, reader.getLineNumber());

        close();
    }

    private void sleepForNewLine() throws InterruptedException {
        Thread.sleep(lineDuration.toMillis());
    }

    /**
     * A closed file appears when the writer rolls the active file, so tailing must yield
     * and let the dir sender publish it.
     */
    private boolean hasClosedFile() {
        File[] files = dir.listFiles(fileFilter);
        return files != null && files.length >= 1;
    }

    private static void closeQuietly(TailQueueStrictLineReader aReader) {
        try {
            aReader.close();
        } catch (IOException e) {
            LOG.debug("Cannot close a reader", e);
        }
    }

}
