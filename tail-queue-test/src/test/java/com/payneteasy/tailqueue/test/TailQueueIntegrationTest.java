package com.payneteasy.tailqueue.test;

import com.payneteasy.tailqueue.ITailQueue;
import com.payneteasy.tailqueue.ITailQueueWriter;
import com.payneteasy.tailqueue.TailQueueBuilder;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class TailQueueIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueIntegrationTest.class );

    @Test
    public void test() throws InterruptedException {
        ITailQueue queue = new TailQueueBuilder()
                .dir(new File("target/" + System.currentTimeMillis()))
                .sender(aLine -> LOG.info("Sending line {}", aLine))
                .build();

        queue.startQueueSender();

        ITailQueueWriter writer = queue.getWriter();

        for(int i=0; i<10; i++) {
            writer.writeMessage("Hello " + i);
            Thread.sleep(200);
        }

        LOG.info("Sleeping 3 seconds ...");
        Thread.sleep(3_000);

        queue.shutdownQueueSender();

        LOG.info("Sleeping another 3 seconds ...");
        Thread.sleep(3_000);
    }
}
