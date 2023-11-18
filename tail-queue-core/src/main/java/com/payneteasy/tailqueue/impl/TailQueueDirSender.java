package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueRetention;
import com.payneteasy.tailqueue.ITailQueueSender;
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

    private final File                dir;
    private final TailQueueFileFilter fileFilter;
    private final ITailQueueSender    sender;
    private final ITailQueueRetention retention;

    public TailQueueDirSender(File dir, TailQueueFileFilter fileFilter, ITailQueueSender sender, ITailQueueRetention retention) {
        this.dir        = dir;
        this.fileFilter = fileFilter;
        this.sender     = sender;
        this.retention  = retention;
    }

    void processDir() {
        List<File> filesToProcess = createFileListForDirProcess();

        if(filesToProcess.isEmpty()) {
            LOG.trace("Files to process {}", filesToProcess.size());
        } else {
            LOG.debug("Files to process {}", filesToProcess.size());
        }

        for (int i = 0; i < filesToProcess.size(); i++) {

            File file = filesToProcess.get(i);

            try {
                sendFile(file, i, filesToProcess.size());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot process file " + file.getAbsolutePath(), e);
            }

            try {
                archiveFile(file, i, filesToProcess.size());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot archive file " + file.getAbsolutePath(), e);
            }
        }
    }

    private void archiveFile(File aFile, int current, int count) {
        LOG.debug("Archiving file ({}/{}) {}...", aFile, current, count);
        retention.archiveFile(aFile);
    }

    private void sendFile(File aFile, int current, int count) throws IOException {
        LOG.debug("Sending file {} ...", aFile.getAbsolutePath());
        try(LineNumberReader in = new LineNumberReader(new InputStreamReader(Files.newInputStream(aFile.toPath()), UTF_8))) {
            String line;
            while( (line = in.readLine()) != null) {
                LOG.debug("Sending line {}:{} ({}/{})...", aFile.getName(), in.getLineNumber(), current, count);
                sender.sendMessage(line);
            }
        }
    }

    private List<File> createFileListForDirProcess() {
        File[] files = dir.listFiles(fileFilter);
        if(files == null || files.length == 0) {
            return Collections.emptyList();
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        // do not send last file
        return Arrays.asList(files).subList(0, files.length - 1);
    }

}
