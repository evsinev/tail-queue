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

    private int     lineNumber = 0;
    private boolean endOfFile  = false;

    public TailQueueStrictLineReader(Reader reader) {
        this.reader = reader;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    public String readLine() throws IOException {

        endOfFile = false;

        for (int i = 0; i < 100_000; i++) {
            int read = reader.read();

            if (read == -1) {
                endOfFile = true;
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

    /**
     * {@link #readLine()} answers null both at the end of the file and for an empty line, so a
     * caller which must not stop reading early asks here which of the two it was.
     *
     * @return true if the last {@link #readLine()} returned null because there was nothing left to
     *         read, false if it returned null because it skipped an empty line
     */
    public boolean isEndOfFile() {
        return endOfFile;
    }

    /**
     * @return true if the reader holds the beginning of a line whose terminating new line has not
     *         arrived. At the end of a file which nobody appends to any more, that content is a line
     *         this reader will never hand out, so a caller which counts the file as delivered must
     *         ask before it does.
     */
    public boolean hasPartialLine() {
        return buffer.length() > 0;
    }
}
