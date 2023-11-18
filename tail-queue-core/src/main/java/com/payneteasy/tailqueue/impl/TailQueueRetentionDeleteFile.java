package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class TailQueueRetentionDeleteFile implements ITailQueueRetention {

    private static final Logger LOG = LoggerFactory.getLogger(TailQueueRetentionDeleteFile.class);

    @Override
    public void archiveFile(File aFile) {
        if (!aFile.delete()) {
            LOG.error("Cannot delete file {}", aFile.getAbsolutePath());
        }
    }
}
