package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueStat;
import com.payneteasy.tailqueue.ITailQueueWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.OutputStreamWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

public class TailQueueWriterImpl implements ITailQueueWriter {

    private static final Logger LOG = LoggerFactory.getLogger(TailQueueWriterImpl.class);

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private final File              dir;
    private final DateTimeFormatter dateFormatter;
    private final String            filePrefix;
    private final String            fileSuffix;
    private final ITailQueueStat    tailQueueStat;

    public TailQueueWriterImpl(File dir, DateTimeFormatter dateFormatter, String filePrefix, String fileSuffix, ITailQueueStat tailQueueStat) {
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

        try (OutputStreamWriter out = new OutputStreamWriter(newOutputStream(file.toPath(), APPEND, CREATE), UTF_8)) {
            out.write(aMessage);
            out.write(LINE_SEPARATOR);
            out.flush();
            tailQueueStat.onMessageWritten();
        } catch (Exception e) {
            tailQueueStat.onMessageWriteError();
            LOG.error("Cannot write to file {} : {}", file.getAbsolutePath(), aMessage, e);
        }
    }

}
