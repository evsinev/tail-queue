package com.payneteasy.tailqueue.impl.util;

import java.io.File;

/**
 * Identity of a file which survives a rename. Internal seam so that a test can simulate a
 * filesystem without file keys; production always uses {@link FileKeys#SYSTEM}.
 */
public interface IFileKeyResolver {

    /**
     * @return an object which equals the key of the same file under any name, or null when the file
     *         does not exist or the filesystem does not expose an identity for it
     */
    Object fileKeyOf(File aFile);
}
