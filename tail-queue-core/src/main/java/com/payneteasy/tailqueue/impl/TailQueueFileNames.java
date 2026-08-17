package com.payneteasy.tailqueue.impl;

import java.io.File;

/**
 * Single source of truth for the on-disk file naming protocol of a queue directory.
 *
 * <ul>
 *     <li>active    - {@code <prefix>current<suffix>.open}, the only file the writer appends to</li>
 *     <li>closed    - {@code <prefix><bucket><suffix>}, published by an atomic rename of the active file</li>
 *     <li>recovered - {@code <prefix><bucket>-recovered<suffix>}, a stale active file left by a crash.
 *                     The marker keeps it out of the way of the plain bucket name and makes it sort
 *                     before the files of the same bucket, because its content is older</li>
 *     <li>failed    - {@code <name>.failed}, a file which was sent but could not be archived</li>
 * </ul>
 *
 * Only closed files are consumed (sent, archived, deleted) by the sender.
 */
public class TailQueueFileNames {

    public static final String OPEN_SUFFIX   = ".open";
    public static final String FAILED_SUFFIX = ".failed";

    private static final String ACTIVE_BUCKET    = "current";
    private static final String RECOVERED_MARKER = "-recovered";
    private static final int    MAX_ATTEMPTS     = 10_000;

    private final String prefix;
    private final String suffix;

    public TailQueueFileNames(String aPrefix, String aSuffix) {
        prefix = aPrefix;
        suffix = aSuffix;
    }

    public File activeFile(File aDir) {
        return new File(aDir, activeFileName());
    }

    public String activeFileName() {
        return prefix + ACTIVE_BUCKET + suffix + OPEN_SUFFIX;
    }

    /**
     * @return true if the file may be sent, archived and deleted by the sender
     */
    public boolean isClosedFile(File aFile) {
        String name = aFile.getName();

        return     name.startsWith(prefix)
                && name.endsWith  (suffix)
                && !name.endsWith (OPEN_SUFFIX)
                && !name.endsWith (FAILED_SUFFIX)
                && !name.equals   (activeFileName());
    }

    /**
     * @return the bucket file for the given bucket, or the first free {@code <bucket>-<n>} variant
     *         if the plain name is already taken. Renaming must never silently overwrite data.
     */
    public File freeBucketFile(File aDir, String aBucket) {
        return firstFree(aDir, aBucket, "");
    }

    /**
     * @return the first free file name for a stale active file rolled at startup
     */
    public File freeRecoveredFile(File aDir, String aBucket) {
        return firstFree(aDir, aBucket, RECOVERED_MARKER);
    }

    /**
     * @return the first free quarantine name for a file which was sent but could not be archived
     */
    public static File freeQuarantineFile(File aFile) {
        File dir = aFile.getParentFile();

        File file = new File(dir, aFile.getName() + FAILED_SUFFIX);
        for (int i = 1; file.exists(); i++) {
            checkAttempt(i, file);
            file = new File(dir, aFile.getName() + FAILED_SUFFIX + "." + i);
        }

        return file;
    }

    private File firstFree(File aDir, String aBucket, String aMarker) {
        File file = new File(aDir, prefix + aBucket + aMarker + suffix);

        for (int i = 1; file.exists(); i++) {
            checkAttempt(i, file);
            file = new File(aDir, prefix + aBucket + aMarker + "-" + i + suffix);
        }

        return file;
    }

    private static void checkAttempt(int aAttempt, File aFile) {
        if (aAttempt >= MAX_ATTEMPTS) {
            throw new IllegalStateException("Cannot find a free file name for " + aFile.getAbsolutePath());
        }
    }
}
