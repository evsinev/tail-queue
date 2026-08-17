package com.payneteasy.tailqueue.impl.util;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

public class SafeFiles {

    public static File mkDirs(File aDir) {
        try {
            Files.createDirectories(aDir.toPath());
            return aDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create directories " + aDir.getAbsolutePath(), e);
        }
    }

    /**
     * Moves a file, preferring an atomic move. The caller must make sure the target does not exist:
     * an atomic move replaces an existing target silently.
     */
    public static void moveFile(File aFrom, File aTo) throws IOException {
        try {
            Files.move(aFrom.toPath(), aTo.toPath(), ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(aFrom.toPath(), aTo.toPath());
        }
    }
}
