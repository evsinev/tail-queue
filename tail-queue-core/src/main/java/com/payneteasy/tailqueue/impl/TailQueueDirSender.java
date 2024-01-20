package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TailQueueDirSender {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueDirSender.class );

    private final File                      dir;
    private final TailQueueFileFilter       fileFilter;
    private final ITailQueueSender          sender;
    private final ITailQueueRetention       retention;
    private final ITailQueueMetricsListener metricsListener;
    private final ITailQueueFileSender      fileSender;

    public TailQueueDirSender(File dir, TailQueueFileFilter fileFilter, ITailQueueSender sender, ITailQueueRetention retention, ITailQueueMetricsListener metricsListener, ITailQueueFileSender fileSender) {
        this.dir             = dir;
        this.fileFilter      = fileFilter;
        this.sender          = sender;
        this.retention       = retention;
        this.metricsListener = metricsListener;
        this.fileSender      = fileSender;
    }

    void processDir() {
        List<File> filesToProcess = createFileListForDirProcess();
        int        count          = filesToProcess.size();

        if(filesToProcess.isEmpty()) {
            LOG.trace("Files to process {}", count);
        } else {
            LOG.debug("Files to process {}", count);
        }

        for (int i = 0; i < count; i++) {

            File file = filesToProcess.get(i);

            try {
                LOG.debug("Sending file {} ...", file.getAbsolutePath());
                sendFile(file, i, count);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot process file " + file.getAbsolutePath(), e);
            }

            try {
                archiveFile(file, i, count);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot archive file " + file.getAbsolutePath(), e);
            }
        }
    }

    private void sendFile(File file, int aCurrent, int aCount) throws IOException {
        TailQueueFileSenderContext context = TailQueueFileSenderContext.builder()
                .file    (file      )
                .current ( aCurrent )
                .count   ( aCount   )
                .sender  ( sender   )
                .metricsListener( metricsListener )
                .build();

        fileSender.sendFile(context);

        metricsListener.didSenderDirSendFile(aCurrent, aCount);
    }

    private void archiveFile(File aFile, int current, int count) {
        LOG.debug("Archiving file ({}/{}) {}...", aFile, current, count);
        retention.archiveFile(aFile);
        metricsListener.didSenderDirArchiveFile();
    }

    private List<File> createFileListForDirProcess() {
        File[] files = dir.listFiles(fileFilter);
        if(files == null || files.length == 0) {
            metricsListener.didSenderDirFilesCount(0);
            return Collections.emptyList();
        }

        metricsListener.didSenderDirFilesCount(files.length - 1);

        Arrays.sort(files, Comparator.comparing(File::getName));
        // do not send last file
        return Arrays.asList(files).subList(0, files.length - 1);
    }

}
