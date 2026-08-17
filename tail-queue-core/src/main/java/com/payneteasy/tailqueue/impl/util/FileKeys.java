package com.payneteasy.tailqueue.impl.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The sender identifies a file by its filesystem key (device and inode on POSIX) and not by its
 * name, because the writer publishes a file by renaming it: the file the tailer reads as the active
 * file becomes a bucket file under a different name, and bucket names are reused across runs.
 */
public class FileKeys {

    private static final Logger LOG = LoggerFactory.getLogger(FileKeys.class);

    public static final IFileKeyResolver SYSTEM = FileKeys::fileKeyOf;

    private FileKeys() {
    }

    /**
     * @return the key of the file, or null when the file does not exist, cannot be read or the
     *         filesystem does not expose file keys. Never throws: a missing key is a normal answer
     *         for a file the writer has just rolled away.
     */
    public static Object fileKeyOf(File aFile) {
        try {
            return Files.readAttributes(aFile.toPath(), BasicFileAttributes.class).fileKey();
        } catch (Exception e) {
            LOG.trace("Cannot read the file key of {}", aFile.getAbsolutePath(), e);
            return null;
        }
    }
}
