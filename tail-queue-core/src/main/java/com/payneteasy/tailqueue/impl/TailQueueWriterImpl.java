package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueMetricsListener;
import com.payneteasy.tailqueue.ITailQueueWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TailQueueWriterImpl implements ITailQueueWriter {

    private static final Logger LOG = LoggerFactory.getLogger(TailQueueWriterImpl.class);

    private final File                      dir;
    private final DateTimeFormatter         dateFormatter;
    private final String                    filePrefix;
    private final String                    fileSuffix;
    private final ITailQueueMetricsListener tailQueueStat;

    public TailQueueWriterImpl(File dir, DateTimeFormatter dateFormatter, String filePrefix, String fileSuffix, ITailQueueMetricsListener tailQueueStat) {
        this.dir           = dir;
        this.dateFormatter = dateFormatter;
        this.filePrefix    = filePrefix;
        this.fileSuffix    = fileSuffix;
        this.tailQueueStat = tailQueueStat;
    }

    @Override
    public synchronized void writeMessage(String aMessage) {
        if (aMessage == null || aMessage.isEmpty()) {
            return;
        }

        byte[] bytes = encodeToBytes(aMessage);

        File file = new File(dir, filePrefix + LocalDateTime.now().format(dateFormatter) + fileSuffix);

        if(LOG.isTraceEnabled()) {
            LOG.trace("Writing to {}: {}", file.getAbsolutePath(), aMessage);
        }

        try(FileOutputStream out = new FileOutputStream(file, true)) {

            out.write(bytes);
            out.flush();

            tailQueueStat.didWriteMessageSuccess();
        } catch (Exception e) {
            tailQueueStat.didWriteMessageError();
            LOG.error("Cannot write to file {} : {}", file.getAbsolutePath(), aMessage, e);
        }
    }

    private static byte[] encodeToBytes(String aMessage) {
        byte[] orig = aMessage.getBytes(UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(orig.length + 1);

        for (byte b : orig) {

            if (b == 0x0A) { // remove \n
                continue;
            }

            if (b == 0x0D) { // remove \r
                continue;
            }

            out.write(b);
        }

        // write new line \n
        out.write(0x0A);

        return out.toByteArray();
    }

}
