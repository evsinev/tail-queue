# queue-sender Specification

## Purpose
Defines the consumption contract for the sender side of a tail-queue directory: which files may be read, archived, or deleted, how the live file is tailed, and what happens when sending, archiving, or the failsafe path fails, so that no message is deleted before it has been delivered or durably diverted.

## Requirements

### Requirement: Only closed files are consumed destructively

The dir sender SHALL read, archive, and delete only closed bucket files. The active (`.open`) file MUST never be archived or deleted by the sender; it MAY only be tailed read-only for low-latency delivery.

#### Scenario: Live file is never deleted

- WHEN the sender processes the directory while the writer is appending to the active file
- THEN the active file remains on disk untouched and only closed bucket files are sent and archived

### Requirement: Archive failure quarantines instead of resending forever

If a file was fully sent but cannot be archived or deleted, the sender SHALL quarantine it (rename with a `.failed` marker) and MUST NOT re-send its content on subsequent cycles within the same process.

#### Scenario: Delete fails after successful send

- WHEN retention fails to delete a sent file
- THEN the file is renamed to a quarantined name, an error metric is emitted, and the next cycle does not send its lines again

### Requirement: Crash recovery of the active file

On startup, a stale active file left by a previous crash SHALL be closed (renamed to a bucket name) so its messages re-enter normal delivery.

#### Scenario: Restart after crash

- WHEN the process restarts and a `.open` file from the previous run exists
- THEN the file is rolled to a closed bucket file and its lines are delivered

### Requirement: Failsafe never silently drops

When all send attempts fail, the failsafe sender SHALL durably write the message to the failsafe directory. If that write also fails, the failsafe sender SHALL throw so the owning cycle does not archive the source file. Thread interruption during retries SHALL preserve the interrupt flag.

The failsafe sender SHALL publish each written message as a closed file immediately instead of waiting for the next bucket, so that a tool scanning the failsafe directory observes every dead letter as soon as it has been written. A failure to publish SHALL NOT be reported to the caller, because the message is already durable; it is retried on the next dead letter or by the startup recovery.

#### Scenario: Dead letter is visible immediately

- WHEN the delegate sender fails all attempts and the message is written to the failsafe directory
- THEN the failsafe directory contains that message in a closed bucket file, with no active file left behind

#### Scenario: Failsafe directory is unwritable

- WHEN the delegate sender fails all attempts and the failsafe write fails (e.g., disk full)
- THEN an exception propagates, the source file is not archived, and the message is retried on a later cycle

#### Scenario: Shutdown during retries

- WHEN the sender thread is interrupted while sleeping between attempts
- THEN the message is written to the failsafe directory and the thread's interrupt flag remains set

### Requirement: Duplicate delivery mode is explicit

A queue SHALL expose a duplicate-delivery mode with exactly two values, fixed for the lifetime of the queue:

- `RESEND`: a closed file is always sent in full, even if the tailer already delivered its lines while the file was active. Each message written to a rolled file is therefore delivered twice in the normal case.
- `SKIP`: a closed file whose lines the tailer already delivered in full is not sent again. Each message is delivered once in the normal case.

The default SHALL be `RESEND`, so that upgrading the library without selecting a mode does not change the observed delivery behavior of an existing deployment.

Both modes SHALL keep at-least-once delivery: no mode may cause a message to be delivered zero times.

#### Scenario: Default mode preserves current behavior

- WHEN a queue is built without selecting a duplicate-delivery mode, a message is written, tailed, and the file is then rolled and processed by the dir sender
- THEN the consumer receives that message twice

#### Scenario: SKIP mode delivers once

- WHEN the same sequence runs on a queue built with `SKIP`
- THEN the consumer receives that message exactly once

### Requirement: A file delivered by the tailer is archived without being sent again

In `SKIP` mode, when the tailer has delivered every line of a file up to its end of file, the sender SHALL treat that file as sent: it SHALL NOT send its content again and SHALL hand it to retention exactly as it does for a file it has just sent, including quarantining it when retention fails.

The record that a file was delivered by the tailer SHALL survive the writer's atomic rename of that file from the active name to its bucket name, and SHALL identify the file itself rather than a name, so that a file which merely reuses a name is never mistaken for a delivered one.

The record SHALL NOT outlive the file it refers to: no closed file SHALL be treated as delivered because it inherited the name or the identity of a file which was delivered earlier.

A closed file the tailer never read — a backlog file, a file left by a previous run, or a file recovered from a crash — SHALL be sent in full in both modes.

#### Scenario: Rolled file is archived, not resent

- WHEN the tailer has delivered all lines of the active file, the writer rolls it to its bucket name, and the dir sender processes the directory in `SKIP` mode
- THEN no line of that file is sent again, the file is archived or deleted by retention, and the skipped-file metric is emitted

#### Scenario: Retention fails for a file delivered by the tailer

- WHEN retention cannot archive or delete such a file
- THEN the file is quarantined exactly as a file which the dir sender itself had sent, and its content is not sent on a later cycle

#### Scenario: The file is gone before the sender could skip it

- WHEN a file the tailer delivered in full leaves the queue dir before the dir sender processes it (it was sent by an earlier cycle, archived out of band, or removed by an operator)
- THEN no record of it is left behind, and a closed file which later happens to carry the same identity is still sent in full

#### Scenario: File the tailer never read

- WHEN a closed bucket file appears in the directory without ever having been tailed (backlog, leftover from a previous run, crash recovery)
- THEN the dir sender sends all of its lines, in `SKIP` mode as well as in `RESEND`

### Requirement: Tailer progress is not lost while a file lives

The sender SHALL NOT deliver a line of a file twice because the tailer stopped reading that file and resumed it on a later cycle. Progress within a file SHALL be kept for as long as that file exists in the queue directory, whatever makes the tailer yield: the appearance of a closed file, a full cycle of the sender task, or an idle period with no new lines.

#### Scenario: Closed file appears while the active file is being tailed

- WHEN the tailer has delivered some lines of the active file and yields because an unrelated closed file appeared in the directory, and a later cycle tails the same active file again
- THEN delivery continues after the last line already delivered, and no earlier line is delivered again

### Requirement: Deduplication never trades away durability

A file SHALL be treated as delivered by the tailer only if every line up to its end of file was accepted by the sender. If delivery of any line failed, or if the tailer stopped before the end of the file, or if the file ends with content the tailer does not deliver as a line, the file SHALL be sent in full by the dir sender even in `SKIP` mode.

#### Scenario: Send fails in the middle of the active file

- WHEN the tailer fails to deliver a line of the active file (the send path, including the failsafe, reports an error) and the file is later rolled
- THEN the dir sender sends that file in full, so the failed message is delivered, accepting duplicates for the lines that had already been delivered

#### Scenario: Rolled file does not end with a complete line

- WHEN a file is published whose last bytes are not terminated as a line, so the tailer has never handed that content to the sender
- THEN the dir sender sends that file in full, so the unterminated content is delivered as its own line, and the file is not counted as delivered by the tailer

#### Scenario: Shutdown while the rolled file is being drained

- WHEN the sender is stopped while the tailer is still delivering the lines of a file which has just been rolled
- THEN the file is not counted as delivered, so the next run sends it in full instead of archiving lines nobody received

### Requirement: File identity is required and its absence fails fast

The sender depends on being able to identify a file across a rename, in both modes: `SKIP` needs it to recognize a file it has already delivered, and `RESEND` needs it to tell a rolled file from an unrelated closed file so that tailer progress is not lost.

If the queue directory is on a filesystem which does not expose a stable file identity, queue construction SHALL fail with an error naming the directory and the selected mode. The sender MUST NOT start with a mode it cannot honor, and MUST NOT report `SKIP` while behaving as `RESEND`.

#### Scenario: Filesystem cannot identify files

- WHEN a queue is built on a directory whose filesystem exposes no stable file identity, in either mode
- THEN construction throws, the error names the directory and the mode, and no sender thread is started

### Requirement: A restart may duplicate but must not lose

Progress of the tailer SHALL NOT be required to survive a process restart. After a restart, an active file which had been partially delivered SHALL be delivered in full, in both modes.

#### Scenario: Restart with a partially tailed active file

- WHEN the process is killed after the tailer delivered part of the active file and the queue is started again on the same directory
- THEN every message of that file is delivered at least once, and the messages delivered before the restart may be delivered again
