package com.payneteasy.tailqueue;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.io.File;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TailQueueFileSenderContext {
    File file;
    int  current;
    int  count;

    ITailQueueSender          sender;
    ITailQueueMetricsListener metricsListener;


}
