## ADDED Requirements

### Requirement: Duplicate delivery mode is explicit

A queue SHALL expose a duplicate-delivery mode with exactly two values, fixed for the lifetime of the queue:

- `RESEND`: a closed file is always sent in full, even if the tailer already delivered its lines while the file was active. Each message written to a rolled file is therefore delivered twice in the normal case.
- `SKIP`: a closed file whose lines the tailer already delivered in full is not sent again. Each message is delivered once in the normal case.

The default SHALL be `RESEND`, so that upgrading the library without selecting a mode does not change the observed delivery behavior of an existing deployment.

Both modes SHALL keep at-least-once delivery: no mode may cause a message to be delivered zero times.

#### Scenario: Default mode preserves current behavior

- **WHEN** a queue is built without selecting a duplicate-delivery mode, a message is written, tailed, and the file is then rolled and processed by the dir sender
- **THEN** the consumer receives that message twice

#### Scenario: SKIP mode delivers once

- **WHEN** the same sequence runs on a queue built with `SKIP`
- **THEN** the consumer receives that message exactly once

### Requirement: A file delivered by the tailer is archived without being sent again

In `SKIP` mode, when the tailer has delivered every line of a file up to its end of file, the sender SHALL treat that file as sent: it SHALL NOT send its content again and SHALL hand it to retention exactly as it does for a file it has just sent, including quarantining it when retention fails.

The record that a file was delivered by the tailer SHALL survive the writer's atomic rename of that file from the active name to its bucket name, and SHALL identify the file itself rather than a name, so that a file which merely reuses a name is never mistaken for a delivered one.

A closed file the tailer never read — a backlog file, a file left by a previous run, or a file recovered from a crash — SHALL be sent in full in both modes.

#### Scenario: Rolled file is archived, not resent

- **WHEN** the tailer has delivered all lines of the active file, the writer rolls it to its bucket name, and the dir sender processes the directory in `SKIP` mode
- **THEN** no line of that file is sent again, the file is archived or deleted by retention, and the skipped-file metric is emitted

#### Scenario: Retention fails for a file delivered by the tailer

- **WHEN** retention cannot archive or delete such a file
- **THEN** the file is quarantined exactly as a file which the dir sender itself had sent, and its content is not sent on a later cycle

#### Scenario: File the tailer never read

- **WHEN** a closed bucket file appears in the directory without ever having been tailed (backlog, leftover from a previous run, crash recovery)
- **THEN** the dir sender sends all of its lines, in `SKIP` mode as well as in `RESEND`

### Requirement: Tailer progress is not lost while a file lives

The sender SHALL NOT deliver a line of a file twice because the tailer stopped reading that file and resumed it on a later cycle. Progress within a file SHALL be kept for as long as that file exists in the queue directory, whatever makes the tailer yield: the appearance of a closed file, a full cycle of the sender task, or an idle period with no new lines.

#### Scenario: Closed file appears while the active file is being tailed

- **WHEN** the tailer has delivered some lines of the active file and yields because an unrelated closed file appeared in the directory, and a later cycle tails the same active file again
- **THEN** delivery continues after the last line already delivered, and no earlier line is delivered again

### Requirement: Deduplication never trades away durability

A file SHALL be treated as delivered by the tailer only if every line up to its end of file was accepted by the sender. If delivery of any line failed, the file SHALL be sent in full by the dir sender even in `SKIP` mode.

#### Scenario: Send fails in the middle of the active file

- **WHEN** the tailer fails to deliver a line of the active file (the send path, including the failsafe, reports an error) and the file is later rolled
- **THEN** the dir sender sends that file in full, so the failed message is delivered, accepting duplicates for the lines that had already been delivered

### Requirement: File identity is required and its absence fails fast

The sender depends on being able to identify a file across a rename, in both modes: `SKIP` needs it to recognize a file it has already delivered, and `RESEND` needs it to tell a rolled file from an unrelated closed file so that tailer progress is not lost.

If the queue directory is on a filesystem which does not expose a stable file identity, queue construction SHALL fail with an error naming the directory and the selected mode. The sender MUST NOT start with a mode it cannot honor, and MUST NOT report `SKIP` while behaving as `RESEND`.

#### Scenario: Filesystem cannot identify files

- **WHEN** a queue is built on a directory whose filesystem exposes no stable file identity, in either mode
- **THEN** construction throws, the error names the directory and the mode, and no sender thread is started

### Requirement: A restart may duplicate but must not lose

Progress of the tailer SHALL NOT be required to survive a process restart. After a restart, an active file which had been partially delivered SHALL be delivered in full, in both modes.

#### Scenario: Restart with a partially tailed active file

- **WHEN** the process is killed after the tailer delivered part of the active file and the queue is started again on the same directory
- **THEN** every message of that file is delivered at least once, and the messages delivered before the restart may be delivered again
