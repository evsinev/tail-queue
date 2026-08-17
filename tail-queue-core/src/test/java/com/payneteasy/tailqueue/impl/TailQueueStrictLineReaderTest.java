package com.payneteasy.tailqueue.impl;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.file.Files;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.StandardOpenOption.APPEND;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both readers tail a file which is still being appended to, so they meet the end of the file in
 * the middle of a message. The tests append and read from the same thread, so every point where
 * the reader hits the end of the file is fixed by the test and not by timing.
 * <p>
 * The difference they document: {@link LineNumberReader} hands out a partial line as soon as it
 * reaches the end of the file, which splits one message into two, while
 * {@link TailQueueStrictLineReader} keeps the partial line in its buffer until the terminating
 * new line arrives.
 */
public class TailQueueStrictLineReaderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File file;

    @Before
    public void setUp() throws IOException {
        file = temporaryFolder.newFile("tailed.json");
    }

    @Test
    public void strictReaderNeverReturnsAPartialLine() throws IOException {
        try (TailQueueStrictLineReader in = new TailQueueStrictLineReader(createReader())) {

            append("First-line\n");
            assertThat(in.readLine()).isEqualTo("First-line");
            assertThat(in.getLineNumber()).isEqualTo(1);

            // nothing to read yet
            assertThat(in.readLine()).isNull();

            append("Second");
            assertThat(in.readLine()).as("a message without its new line is not a line yet").isNull();
            assertThat(in.getLineNumber()).isEqualTo(1);

            append("-line\n");
            assertThat(in.readLine()).as("the buffered part is completed, not split").isEqualTo("Second-line");
            assertThat(in.getLineNumber()).isEqualTo(2);

            append("\n");
            assertThat(in.readLine()).as("an empty line is skipped").isNull();
            assertThat(in.getLineNumber()).isEqualTo(3);

            append("Third-line\n");
            assertThat(in.readLine()).isEqualTo("Third-line");
            assertThat(in.getLineNumber()).isEqualTo(4);

            assertThat(in.readLine()).isNull();
        }
    }

    @Test
    public void lineNumberReaderReturnsAPartialLineAtEndOfFile() throws IOException {
        try (LineNumberReader in = new LineNumberReader(createReader())) {

            append("First-line\n");
            assertThat(in.readLine()).isEqualTo("First-line");
            assertThat(in.getLineNumber()).isEqualTo(1);

            assertThat(in.readLine()).isNull();

            append("Second");
            assertThat(in.readLine()).as("half of a message is handed out as a line").isEqualTo("Second");
            assertThat(in.getLineNumber()).isEqualTo(2);

            append("-line\n");
            assertThat(in.readLine()).as("and its second half becomes another line").isEqualTo("-line");
            assertThat(in.getLineNumber()).isEqualTo(3);

            append("\n");
            assertThat(in.readLine()).isEmpty();
            assertThat(in.getLineNumber()).isEqualTo(4);

            append("Third-line\n");
            assertThat(in.readLine()).isEqualTo("Third-line");
            assertThat(in.getLineNumber()).isEqualTo(5);

            assertThat(in.readLine()).isNull();
        }
    }

    private InputStreamReader createReader() throws IOException {
        return new InputStreamReader(newInputStream(file.toPath()), UTF_8);
    }

    private void append(String aText) throws IOException {
        Files.write(file.toPath(), aText.getBytes(UTF_8), APPEND);
    }
}
