# queue-writer — delta

## Purpose

Defines the durability contract for writing messages into a tail-queue directory: how messages are framed as lines, how the active file is named and rolled, and how write failures are reported, so that an acknowledged message can always be recovered from disk.

## ADDED Requirements

### Requirement: Active file protocol

The writer SHALL append messages only to a single active file whose name is distinguishable from closed bucket files (suffix `.open`). When the time bucket changes, the writer SHALL close the active file by atomically renaming it to its bucket file name before creating a new active file. A file with a bucket name MUST never receive further appends.

#### Scenario: Roll on bucket change

- WHEN a message is written in bucket B1 and the next message is written in bucket B2
- THEN the B1 active file is renamed to the B1 bucket name before the B2 message is appended to a new active file

#### Scenario: Clock steps backwards

- WHEN the system clock moves backwards (NTP step) between two writes
- THEN the writer continues appending to the current active file or rolls forward, but MUST NOT append to any already-closed bucket file

### Requirement: Bucket names are UTC and sort chronologically

Bucket file names SHALL be derived from UTC time and SHALL sort lexicographically in chronological order for every roll cycle, including TEN_MINUTELY.

#### Scenario: TEN_MINUTELY ordering

- WHEN files are produced at 10:09 and 10:10 UTC with the TEN_MINUTELY cycle
- THEN both timestamps map to distinct or identical buckets per the 10-minute rule and the resulting file names sort in chronological order

### Requirement: Line framing survives torn writes

Every message SHALL occupy exactly one `\n`-terminated line. Before appending to an active file whose last byte is not `\n` (e.g., after a crash mid-write), the writer SHALL first write a `\n` so that a torn previous write can never be concatenated with the next message.

#### Scenario: Crash between two writes

- WHEN the process is killed after writing only part of message M1 and later appends message M2
- THEN M2 is delivered intact as its own line and the partial M1 bytes form a separate (possibly truncated) line

### Requirement: Write errors are reported

In strict mode the writer SHALL throw on any failure to persist a message (I/O error, disk full), so the caller can react. In lenient mode (default, backward compatible) failures SHALL be logged and counted via the metrics listener.

#### Scenario: Disk full in strict mode

- WHEN the underlying filesystem returns an error during append and strict mode is enabled
- THEN `writeMessage` throws and the metrics error counter is incremented

### Requirement: Optional fsync

The writer SHALL support a configurable fsync policy. With `EVERY_MESSAGE`, a successful `writeMessage` return guarantees the message has been forced to stable storage.

#### Scenario: Power loss after acknowledged write

- WHEN fsync policy is `EVERY_MESSAGE` and power is lost immediately after `writeMessage` returns
- THEN the message is present in the queue file after restart
