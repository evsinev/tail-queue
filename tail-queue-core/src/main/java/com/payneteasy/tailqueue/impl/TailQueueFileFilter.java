package com.payneteasy.tailqueue.impl;

import java.io.File;
import java.io.FileFilter;

public class TailQueueFileFilter implements FileFilter {

    private final String prefix;
    private final String suffix;

    public TailQueueFileFilter(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
    }

    @Override
    public boolean accept(File aFile) {
        String filename = aFile.getName();

        return     aFile.isFile()
                && filename.startsWith(prefix)
                && filename.endsWith  (suffix);
    }

}
