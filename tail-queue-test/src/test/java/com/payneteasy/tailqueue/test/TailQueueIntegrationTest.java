package com.payneteasy.tailqueue.test;

import com.payneteasy.tailqueue.ITailQueue;
import com.payneteasy.tailqueue.ITailQueueSender;
import com.payneteasy.tailqueue.ITailQueueWriter;
import com.payneteasy.tailqueue.TailQueueBuilder;
import com.payneteasy.tailqueue.impl.TailQueueRetentionArchiver;
import com.payneteasy.tailqueue.impl.TailQueueSenderFailsafe;
import com.payneteasy.tailqueue.prometheus.simpleclient.TailQueuePrometheusSimpleClientFactory;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;

public class TailQueueIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueIntegrationTest.class );

    @Test
    public void test() throws InterruptedException {
        TailQueuePrometheusSimpleClientFactory metricsFactory = new TailQueuePrometheusSimpleClientFactory();

        AtomicInteger attempt = new AtomicInteger(0);
        ITailQueueSender sender = aLine -> {
            if(attempt.incrementAndGet() % 5 == 0 ) {
                throw new IllegalStateException("Simple error while sending");
            }
            LOG.info("Sending line {}", aLine);
        };
        
        File dir = new File("target/" + System.currentTimeMillis());
        ITailQueue queue = new TailQueueBuilder()
                .dir(dir)
                .sender(new TailQueueSenderFailsafe(
                        new File(dir, "failsafe")
                        , 3
                        , Duration.ofMillis(100)
                        , sender
                ))
                .metricsListener(metricsFactory.createMetricsListener("test"))
                .retention(new TailQueueRetentionArchiver(new File(dir, "processed")))
                .build();

        queue.startQueueSender();

        ITailQueueWriter writer = queue.getWriter();

        for(int i=0; i<10; i++) {
            writer.writeMessage("Hello " + i);
            Thread.sleep(200);
        }

        LOG.info("Sleeping 2 seconds before shutdown ...");
        Thread.sleep(2_000);

        queue.shutdownQueueSender();

        LOG.info("Sleeping another 2 seconds after queue shutdown ...");
        Thread.sleep(2_000);

        Enumeration<Collector.MetricFamilySamples> en = CollectorRegistry.defaultRegistry.metricFamilySamples();
        while (en.hasMoreElements()) {
            Collector.MetricFamilySamples samples = en.nextElement();
            LOG.info("Sample {}", samples);
        }
    }
}
