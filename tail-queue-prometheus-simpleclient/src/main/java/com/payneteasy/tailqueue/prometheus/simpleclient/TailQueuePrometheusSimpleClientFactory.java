package com.payneteasy.tailqueue.prometheus.simpleclient;

import com.payneteasy.tailqueue.ITailQueueMetricsListener;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

public class TailQueuePrometheusSimpleClientFactory {

    private final Counter writeMessageSuccess          = counter ("write_message_success");
    private final Counter writeMessageError            = counter ("write_message_error");
    private final Counter senderTaskErrorProcessingDir = counter ("sender_task_processing_dir_error");
    private final Counter senderDirArchiveFile         = counter ("sender_dir_archive_file");
    private final Gauge   senderDirSendFileCurrent     = gauge   ("sender_dir_send_file_current");
    private final Gauge   senderDirSendFileCount       = gauge   ("sender_dir_send_file_count");
    private final Gauge   senderDirFilesCount          = gauge   ("sender_dir_files_count");
    private final Counter senderFileError              = counter ("sender_file_error");
    private final Gauge   senderFileSendLine           = gauge   ("sender_file_line");
    private final Counter senderDirSendLineSuccess     = counter ("sender_dir_send_line_success");
    private final Counter senderFileSendLineSuccess    = counter ("sender_file_send_line_success");

    private static Counter counter(String aSuffix) {
        String name = "tail_queue_" + aSuffix;
        
        return Counter.build()
                .name       ( name )
                .help       ( name.replace('_', ' '))
                .labelNames ( "queue" )
                .register();
    }

    private static Gauge gauge(String aSuffix) {
        String name = "tail_queue_" + aSuffix;
        
        return Gauge.build()
                .name       ( name )
                .help       ( name.replace('_', ' '))
                .labelNames ( "queue" )
                .register();
    }


    public ITailQueueMetricsListener createMetricsListener(String aName) {
        return new TailQueueMetricsListenerPrometheusSimpleClient(
                aName
                , writeMessageSuccess.labels(aName)
                , writeMessageError.labels(aName)
                , senderTaskErrorProcessingDir.labels(aName)
                , senderDirArchiveFile.labels(aName)
                , senderDirSendFileCurrent.labels(aName)
                , senderDirSendFileCount.labels(aName)
                , senderDirFilesCount.labels(aName)
                , senderFileError.labels(aName)
                , senderFileSendLine.labels(aName)
                , senderDirSendLineSuccess.labels(aName)
                , senderFileSendLineSuccess.labels(aName)
        );
    }
}
