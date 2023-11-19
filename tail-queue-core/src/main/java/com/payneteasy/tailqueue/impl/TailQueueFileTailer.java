package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueMetricsListener;
import com.payneteasy.tailqueue.ITailQueueSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;

public class TailQueueFileTailer {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueFileTailer.class );

    private final File                      dir;
    private final ITailQueueSender          sender;
    private final TailQueueFileFilter       fileFilter;
    private final Duration                  lineDuration;
    private final ITailQueueMetricsListener metricsListener;

    public TailQueueFileTailer(File dir, ITailQueueSender sender, TailQueueFileFilter fileFilter, Duration lineDuration, ITailQueueMetricsListener metricsListener) {
        this.dir             = dir;
        this.sender          = sender;
        this.fileFilter      = fileFilter;
        this.lineDuration    = lineDuration;
        this.metricsListener = metricsListener;
    }

    public void tailOneFile() throws InterruptedException {
        Optional<File> fileOpt = findOneFile();
        if (!fileOpt.isPresent()) {
            return;
        }

        tailFile(fileOpt.get());
    }

    private void tailFile(File aFile) throws InterruptedException {
        LOG.debug("Start tailing file {} ...", aFile.getAbsolutePath());
        
        try (LineNumberReader in = new LineNumberReader(new InputStreamReader(Files.newInputStream(aFile.toPath()), StandardCharsets.UTF_8))) {
            while (!Thread.currentThread().isInterrupted()) {

                String line = in.readLine();

                if (line != null) {
                    LOG.debug("Send line {}:{} ...", aFile.getName(), in.getLineNumber());
                    sender.sendMessage(line);
                    metricsListener.didSenderFileSendLine(in.getLineNumber());
                    continue;
                }

                if (hasAnotherFile()) {
                    LOG.debug("Found another file. Exiting ...");
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

    private Optional<File> findOneFile() {
        File[] files = dir.listFiles(fileFilter);
        return files != null && files.length == 1 ? Optional.of(files[0]) : Optional.empty();
    }

    private boolean hasAnotherFile() {
        File[] files = dir.listFiles(fileFilter);
        return files != null && files.length >= 2;
    }

}
