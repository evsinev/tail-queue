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

#### Scenario: Failsafe directory is unwritable

- WHEN the delegate sender fails all attempts and the failsafe write fails (e.g., disk full)
- THEN an exception propagates, the source file is not archived, and the message is retried on a later cycle

#### Scenario: Shutdown during retries

- WHEN the sender thread is interrupted while sleeping between attempts
- THEN the message is written to the failsafe directory and the thread's interrupt flag remains set
