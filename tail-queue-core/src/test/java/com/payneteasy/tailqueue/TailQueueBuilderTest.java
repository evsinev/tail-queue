package com.payneteasy.tailqueue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TailQueueBuilderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File dir;

    @Before
    public void setUp() throws IOException {
        dir = temporaryFolder.newFolder("queue");
    }

    /**
     * Both modes need to recognize a file after the writer has renamed it, so a filesystem without
     * file keys is refused at construction instead of silently delivering the active file's lines
     * again on every cycle.
     */
    @Test
    public void refusesToBuildWhenTheFilesystemHasNoFileKeys() {
        for (TailQueueDuplicatePolicy policy : TailQueueDuplicatePolicy.values()) {
            assertThatThrownBy(() -> createBuilder()
                    .duplicatePolicy(policy)
                    .fileKeys(aFile -> null)
                    .build())
                    .as("policy %s", policy)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(dir.getAbsolutePath())
                    .hasMessageContaining(policy.name());
        }
    }

    @Test
    public void buildsOnAFilesystemWhichExposesFileKeys() {
        for (TailQueueDuplicatePolicy policy : TailQueueDuplicatePolicy.values()) {
            assertThat(createBuilder().duplicatePolicy(policy).build()).isNotNull();
        }
    }

    private TailQueueBuilder createBuilder() {
        return new TailQueueBuilder()
                .dir(dir)
                .sender(aMessage -> { /* nothing to do */ });
    }
}
