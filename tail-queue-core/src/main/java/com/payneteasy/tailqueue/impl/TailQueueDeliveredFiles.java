package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.TailQueueDuplicatePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

import static com.payneteasy.tailqueue.TailQueueDuplicatePolicy.SKIP;

/**
 * Files which the tailer has delivered up to their end of file, identified by their filesystem key
 * so that the record survives the rename by which the writer publishes a file.
 * <p>
 * The tailer adds an entry, the dir sender consumes it. Both run in the sender task thread, one
 * after the other, so no synchronization is needed and the record never has to reach the disk: a
 * process restart simply forgets it, and the file is delivered again.
 */
public class TailQueueDeliveredFiles {

    private static final Logger LOG = LoggerFactory.getLogger(TailQueueDeliveredFiles.class);

    /**
     * The dir sender consumes an entry on the very next cycle, so a handful is all that is ever
     * needed. The bound only keeps a pathological case (retention permanently failing) from growing
     * the set without limit; dropping an entry costs a duplicate, never a lost message.
     */
    private static final int MAX_SIZE = 1024;

    private final Set<Object> fileKeys = new LinkedHashSet<>();

    private final boolean enabled;

    public TailQueueDeliveredFiles(TailQueueDuplicatePolicy aPolicy) {
        enabled = aPolicy == SKIP;
    }

    /**
     * @return false when the queue resends every closed file, in which case nothing is recorded and
     *         the dir sender does not have to resolve a file key at all
     */
    public boolean isEnabled() {
        return enabled;
    }

    public void add(Object aFileKey) {
        if (!enabled || aFileKey == null) {
            return;
        }

        if (fileKeys.size() >= MAX_SIZE) {
            LOG.warn("Too many files delivered by the tailer and not yet processed by the dir sender ({}),"
                    + " {} will be sent again", MAX_SIZE, aFileKey);
            return;
        }

        fileKeys.add(aFileKey);
    }

    /**
     * @return true if the tailer had delivered this file in full. The entry is consumed, because the
     *         dir sender archives the file right after asking.
     */
    public boolean consume(Object aFileKey) {
        return enabled && aFileKey != null && fileKeys.remove(aFileKey);
    }
}
