package com.payneteasy.tailqueue.test;

import com.payneteasy.tailqueue.impl.TailQueueStrictLineReader;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newInputStream;
import static org.assertj.core.api.Assertions.assertThat;

public class TailQueueStrictLineReaderTest {

    private static final Logger LOG = LoggerFactory.getLogger( TailQueueStrictLineReaderTest.class );

    @Test
    public void strict_reader() throws IOException {
        File file = createAndStartWritingFile();

        try(TailQueueStrictLineReader in = new TailQueueStrictLineReader(new InputStreamReader(newInputStream(file.toPath()), UTF_8))) {
            assertThat(waitLine(in)).isEqualTo("First-line");
            assertThat(in.getLineNumber()).isEqualTo(1);

            assertThat(waitLine(in)).isEqualTo("Second-line");
            assertThat(in.getLineNumber()).isEqualTo(2);

            assertThat(waitLine(in)).isEqualTo("Third-line");
            assertThat(in.getLineNumber()).isEqualTo(3);

            assertThat(waitLine(in)).isEqualTo("Forth-line");
            assertThat(in.getLineNumber()).isEqualTo(4);

            assertThat(waitLine(in)).isEqualTo("Fifth-line");
            assertThat(in.getLineNumber()).isEqualTo(7);
        }
    }

    @Test
    public void line_number_reader() throws IOException {
        File file = createAndStartWritingFile();

        try(LineNumberReader in = new LineNumberReader(new InputStreamReader(newInputStream(file.toPath()), UTF_8))) {
            assertThat(waitLine(in)).isEqualTo("First-line");

            assertThat(waitLine(in)).isEqualTo("Second-line");
            assertThat(in.getLineNumber()).isEqualTo(2);

            assertThat(waitLine(in)).isEqualTo("Third-line");
            assertThat(in.getLineNumber()).isEqualTo(4);

            assertThat(waitLine(in)).isEqualTo("Forth-line");
            assertThat(in.getLineNumber()).isEqualTo(5);

            assertThat(waitLine(in)).isEqualTo("Fifth-line");
            assertThat(in.getLineNumber()).isEqualTo(8);
        }
    }

    private File createAndStartWritingFile() {
        File file = new File("target/strict-line-test-" + System.currentTimeMillis() + ".txt");
        writeToFile(file, "First-line", "\n");

        Thread writerThread = new Thread(() -> {
            writeToFile(file, "Second", "-line");
            writeToFile(file, "\n");
            writeToFile(file, "Third-line\n");
            writeToFile(file, "Forth");
            writeToFile(file, "-line");
            writeToFile(file, "\n");
            writeToFile(file, "\n");
            writeToFile(file, "\n");
            writeToFile(file, "Fifth");
            writeToFile(file, "");
            writeToFile(file, "-line");
            writeToFile(file, "\n");
        });
        writerThread.start();
        return file;
    }

    private static String waitLine(LineNumberReader in) throws IOException {
        for (int i = 0; i < 10; i++) {
            String line = in.readLine();
            if (line != null && !line.isEmpty()) {
                LOG.debug("Read line '{}'", line);
                return line;
            }
            try {
                LOG.debug("Waiting line ...");
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted read", e);
            }
        }
        throw new IllegalStateException("Cannot wait for new line");
    }

    private static String waitLine(TailQueueStrictLineReader in) throws IOException {
        for (int i = 0; i < 10; i++) {
            String line = in.readLine();
            if (line != null) {
                LOG.debug("Read line '{}'", line);
                return line;
            }
            try {
                LOG.debug("Waiting line ...");
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted read", e);
            }
        }
        throw new IllegalStateException("Cannot wait for new line");
    }

    private void writeToFile(File aFile, String... texts) {
        if (texts == null) {
            throw new IllegalStateException("No texts");
        }

        try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(aFile, true), UTF_8)) {
            for (String text : texts) {
                LOG.debug("Writing '{}' ...", text.replace("\n", "\\n"));
                out.write(text);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write " + Arrays.asList(texts) + " to file " + aFile.getAbsolutePath(), e);
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw new IllegalStateException("Interrupted write", e);
        }
    }
}
