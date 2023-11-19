package com.payneteasy.tailqueue.impl.util;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

public class SafeFiles {

    public static File mkDirs(File aDir) {
        try {
            Files.createDirectories(aDir.toPath());
            return aDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create directories " + aDir.getAbsolutePath(), e);
        }
    }
}
