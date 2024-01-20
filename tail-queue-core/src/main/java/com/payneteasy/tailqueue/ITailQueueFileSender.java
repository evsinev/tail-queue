package com.payneteasy.tailqueue;

import java.io.IOException;

public interface ITailQueueFileSender {

    void sendFile(TailQueueFileSenderContext aContext) throws IOException;

}
