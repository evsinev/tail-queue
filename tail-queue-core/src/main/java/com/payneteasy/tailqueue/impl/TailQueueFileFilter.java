package com.payneteasy.tailqueue.impl;

import java.io.File;
import java.io.FileFilter;

/**
 * Accepts only closed queue files: the active ({@code .open}) file the writer is appending to
 * and quarantined ({@code .failed}) files are never consumed by the sender.
 */
public class TailQueueFileFilter implements FileFilter {

    private final TailQueueFileNames fileNames;

    public TailQueueFileFilter(String prefix, String suffix) {
        this(new TailQueueFileNames(prefix, suffix));
    }

    public TailQueueFileFilter(TailQueueFileNames fileNames) {
        this.fileNames = fileNames;
    }

    @Override
    public boolean accept(File aFile) {
        return aFile.isFile() && fileNames.isClosedFile(aFile);
    }

}
