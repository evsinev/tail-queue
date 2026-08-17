package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.*;
import com.payneteasy.tailqueue.impl.util.IFileKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.payneteasy.tailqueue.impl.TailQueueFileNames.freeQuarantineFile;
import static com.payneteasy.tailqueue.impl.util.SafeFiles.moveFile;

public class TailQueueDirSender {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueDirSender.class );

    private final File                      dir;
    private final TailQueueFileFilter       fileFilter;
    private final ITailQueueSender          sender;
    private final ITailQueueRetention       retention;
    private final ITailQueueMetricsListener metricsListener;
    private final ITailQueueFileSender      fileSender;
    private final IFileKeyResolver          fileKeys;
    private final TailQueueDeliveredFiles   deliveredFiles;

    /** files which were sent but could neither be archived nor quarantined; never sent again */
    private final Set<String> sentFiles = new HashSet<>();

    public TailQueueDirSender(
              File                      dir
            , TailQueueFileFilter       fileFilter
            , ITailQueueSender          sender
            , ITailQueueRetention       retention
            , ITailQueueMetricsListener metricsListener
            , ITailQueueFileSender      fileSender
            , IFileKeyResolver          fileKeys
            , TailQueueDeliveredFiles   deliveredFiles
    ) {
        this.dir             = dir;
        this.fileFilter      = fileFilter;
        this.sender          = sender;
        this.retention       = retention;
        this.metricsListener = metricsListener;
        this.fileSender      = fileSender;
        this.fileKeys        = fileKeys;
        this.deliveredFiles  = deliveredFiles;
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

            if (wasDeliveredByTailer(file)) {
                LOG.debug("File {} was already delivered by the tailer, archiving it without sending", file.getAbsolutePath());
                metricsListener.didSenderDirSkipFile();
            } else {
                try {
                    LOG.debug("Sending file {} ...", file.getAbsolutePath());
                    sendFile(file, i, count);
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot process file " + file.getAbsolutePath(), e);
                }
            }

            archiveFile(file, i, count);
        }
    }

    /**
     * A file which the tailer read to its end of file has already been delivered line by line, so
     * only retention is left to do. The queue resends every file unless it was built with
     * {@link com.payneteasy.tailqueue.TailQueueDuplicatePolicy#SKIP}.
     */
    private boolean wasDeliveredByTailer(File aFile) {
        if (!deliveredFiles.isEnabled()) {
            return false;
        }

        Object fileKey = fileKeys.fileKeyOf(aFile);

        if (fileKey == null) {
            LOG.warn("Cannot resolve the file key of {}, sending it again", aFile.getAbsolutePath());
            return false;
        }

        return deliveredFiles.consume(fileKey);
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

        try {
            retention.archiveFile(aFile);
        } catch (Exception e) {
            LOG.error("Cannot archive file {}", aFile.getAbsolutePath(), e);
        }

        if (!aFile.exists()) {
            metricsListener.didSenderDirArchiveFile();
            return;
        }

        quarantineFile(aFile);
    }

    /**
     * The file was fully sent but retention could not archive or delete it. Sending it again on the
     * next cycle would duplicate its content forever, so it is moved out of the way for the ops team.
     */
    private void quarantineFile(File aFile) {
        File quarantined = freeQuarantineFile(aFile);

        try {
            moveFile(aFile, quarantined);
            LOG.error("Cannot archive file {}, quarantined it to {}", aFile.getAbsolutePath(), quarantined.getAbsolutePath());

        } catch (Exception e) {
            // last resort: remember the file for the process lifetime so it is not sent again
            sentFiles.add(aFile.getName());
            LOG.error("Cannot quarantine file {}, it will be skipped until the process restarts", aFile.getAbsolutePath(), e);
        }

        metricsListener.didSenderDirQuarantineFile();
    }

    private List<File> createFileListForDirProcess() {
        File[] files = dir.listFiles(fileFilter);
        if(files == null || files.length == 0) {
            metricsListener.didSenderDirFilesCount(0);
            return Collections.emptyList();
        }

        List<File> filesToProcess = new ArrayList<>(files.length);
        for (File file : files) {
            if (!sentFiles.contains(file.getName())) {
                filesToProcess.add(file);
            }
        }

        metricsListener.didSenderDirFilesCount(filesToProcess.size());

        // the active file the writer appends to is not in the list: all these files are closed
        filesToProcess.sort(Comparator.comparing(File::getName));

        return filesToProcess;
    }

}
