package com.payneteasy.tailqueue.impl;

import com.payneteasy.tailqueue.ITailQueueFileSender;
import com.payneteasy.tailqueue.TailQueueFileSenderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newInputStream;

public class TailQueueFileSenderImpl implements ITailQueueFileSender {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueFileSenderImpl.class );

    @Override
    public void sendFile(TailQueueFileSenderContext aContext) throws IOException {
        try(LineNumberReader in = new LineNumberReader(new InputStreamReader(newInputStream(aContext.getFile().toPath()), UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                LOG.debug("Sending line {}:{} ({}/{})..."
                        , aContext.getFile().getName()
                        , in.getLineNumber()
                        , aContext.getCurrent()
                        , aContext.getCount()
                );
                aContext.getSender().sendMessage(line);
                aContext.getMetricsListener().didSenderDirSendLineSuccess();
            }
        }
    }
}
