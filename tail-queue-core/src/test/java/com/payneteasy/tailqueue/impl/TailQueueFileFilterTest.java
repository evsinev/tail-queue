package com.payneteasy.tailqueue.impl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class TailQueueFileFilterTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final TailQueueFileFilter filter = new TailQueueFileFilter("queue-", ".json");

    @Test
    public void acceptsClosedBucketFiles() throws IOException {
        assertThat(filter.accept(file("queue-20260817-1000.json"))).isTrue();
        assertThat(filter.accept(file("queue-20260817-1000-recovered.json"))).isTrue();
    }

    @Test
    public void rejectsTheActiveFile() throws IOException {
        assertThat(filter.accept(file("queue-current.json.open"))).isFalse();
    }

    @Test
    public void rejectsQuarantinedFiles() throws IOException {
        assertThat(filter.accept(file("queue-20260817-1000.json.failed"))).isFalse();
        assertThat(filter.accept(file("queue-20260817-1000.json.failed.1"))).isFalse();
    }

    @Test
    public void rejectsForeignFilesAndDirectories() throws IOException {
        assertThat(filter.accept(file("other-20260817-1000.json"))).isFalse();
        assertThat(filter.accept(file("queue-20260817-1000.txt"))).isFalse();
        assertThat(filter.accept(temporaryFolder.newFolder("queue-subdir.json"))).isFalse();
    }

    private File file(String aName) throws IOException {
        return temporaryFolder.newFile(aName);
    }
}
