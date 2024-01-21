package com.payneteasy.tailqueue.impl;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

/**
 * Not thread safe
 */
public class TailQueueStrictLineReader implements Closeable {

    private final Reader        reader;
    private final StringBuilder buffer = new StringBuilder();

    private int lineNumber = 0;

    public TailQueueStrictLineReader(Reader reader) {
        this.reader = reader;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    public String readLine() throws IOException {

        for (int i = 0; i < 100_000; i++) {
            int read = reader.read();

            if (read == -1) {
                return null;
            }

            char ch = (char) read;

            if (ch == '\n') {
                return getLineAndEmptyBuffer();
            }

            buffer.append(ch);
        }

        return null;
    }

    private String getLineAndEmptyBuffer() {
        String line = buffer.toString();
        buffer.delete(0, buffer.length());
        lineNumber++;
        return line.isEmpty() ? null : line;
    }

    public int getLineNumber() {
        return lineNumber;
    }
}
