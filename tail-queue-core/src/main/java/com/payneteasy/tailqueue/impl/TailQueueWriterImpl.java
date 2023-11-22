package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueMetricsListener;
import com.payneteasy.tailqueue.ITailQueueWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

public class TailQueueWriterImpl implements ITailQueueWriter {

    private static final Logger LOG = LoggerFactory.getLogger(TailQueueWriterImpl.class);

    private static final String LINE_SEPARATOR = System.lineSeparator();

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
        File file = new File(dir, filePrefix + LocalDateTime.now().format(dateFormatter) + fileSuffix);

        if(LOG.isTraceEnabled()) {
            LOG.trace("Writing to {}: {}", file.getAbsolutePath(), aMessage);
        }

        try(FileOutputStream out = new FileOutputStream(file, true)) {
            String messageWithEndLine = aMessage + LINE_SEPARATOR;
            byte[] bytes              = messageWithEndLine.getBytes(UTF_8);

            out.write(bytes);
            out.flush();

            tailQueueStat.didWriteMessageSuccess();
        } catch (Exception e) {
            tailQueueStat.didWriteMessageError();
            LOG.error("Cannot write to file {} : {}", file.getAbsolutePath(), aMessage, e);
        }
    }

}
