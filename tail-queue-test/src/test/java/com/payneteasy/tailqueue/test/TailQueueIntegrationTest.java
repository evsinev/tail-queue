package com.payneteasy.tailqueue.test;

import com.payneteasy.tailqueue.ITailQueue;
import com.payneteasy.tailqueue.ITailQueueWriter;
import com.payneteasy.tailqueue.TailQueueBuilder;
import com.payneteasy.tailqueue.prometheus.simpleclient.TailQueuePrometheusSimpleClientFactory;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Enumeration;

public class TailQueueIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueIntegrationTest.class );

    @Test
    public void test() throws InterruptedException {
        TailQueuePrometheusSimpleClientFactory metricsFactory = new TailQueuePrometheusSimpleClientFactory();

        ITailQueue queue = new TailQueueBuilder()
                .dir(new File("target/" + System.currentTimeMillis()))
                .sender(aLine -> LOG.info("Sending line {}", aLine))
                .metricsListener(metricsFactory.createMetricsListener("test"))
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
