package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.GZIPOutputStream;

public class TailQueueRetentionArchiver implements ITailQueueRetention {

    private static final Logger LOG = LoggerFactory.getLogger(TailQueueRetentionArchiver.class);

    private final File processedDir;

    public TailQueueRetentionArchiver(File processedDir) {
        this.processedDir = processedDir;
    }

    @Override
    public void archiveFile(File file) {
        File processedFile = new File(processedDir, file.getName() + ".gz");
        LOG.info("Archiving file {}  to {} ...", file.getAbsolutePath(), processedFile.getAbsolutePath());
        try {
            try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(processedFile.toPath()))) {
                java.nio.file.Files.copy(file.toPath(), out);
            }
            if (!file.delete()) {
                LOG.error("Cannot delete file {}", file.getAbsolutePath());
            }
        } catch (IOException e) {
            LOG.error("Cannot move file {} to {}", file.getAbsolutePath(), processedFile.getAbsolutePath(), e);
        }
    }
}
