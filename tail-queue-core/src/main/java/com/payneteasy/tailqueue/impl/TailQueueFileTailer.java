package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueMetricsListener;
import com.payneteasy.tailqueue.ITailQueueSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStreamReader;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newInputStream;

public class TailQueueFileTailer {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueFileTailer.class );

    private final File                      dir;
    private final ITailQueueSender          sender;
    private final TailQueueFileFilter       fileFilter;
    private final TailQueueFileNames        fileNames;
    private final Duration                  lineDuration;
    private final ITailQueueMetricsListener metricsListener;

    public TailQueueFileTailer(File dir, ITailQueueSender sender, TailQueueFileFilter fileFilter, TailQueueFileNames fileNames, Duration lineDuration, ITailQueueMetricsListener metricsListener) {
        this.dir             = dir;
        this.sender          = sender;
        this.fileFilter      = fileFilter;
        this.fileNames       = fileNames;
        this.lineDuration    = lineDuration;
        this.metricsListener = metricsListener;
    }

    /**
     * Tails the active file the writer is appending to. The file is only ever read: it is
     * published by the writer itself, and only then sent and archived by the dir sender.
     */
    public void tailOneFile() throws InterruptedException {
        File active = fileNames.activeFile(dir);

        if (!active.isFile()) {
            return;
        }

        tailFile(active);
    }

    private void tailFile(File aFile) throws InterruptedException {
        LOG.debug("Start tailing file {} ...", aFile.getAbsolutePath());
        
        try (TailQueueStrictLineReader in = new TailQueueStrictLineReader(new InputStreamReader(newInputStream(aFile.toPath()), UTF_8))) {
            while (!Thread.currentThread().isInterrupted()) {

                String line = in.readLine();

                if (line != null) {
                    LOG.debug("Send line {}:{} ...", aFile.getName(), in.getLineNumber());
                    sender.sendMessage(line);
                    metricsListener.didSenderFileSendLine(in.getLineNumber());
                    metricsListener.didSenderFileSendLineSuccess();
                    continue;
                }

                if (hasClosedFile()) {
                    LOG.debug("Found a closed file. Exiting ...");
                    return;
                }

                sleepForNewLine();
            }
        } catch (InterruptedException e) {
            LOG.warn("Tailing file interrupted {}", aFile.getAbsolutePath());
            throw e;
        } catch (Exception e) {
            LOG.error("Cannot process file {}", aFile.getAbsolutePath(), e);
            metricsListener.didSenderFileError();
        }
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

}
