# Proposal: fix-message-loss-edge-cases

## Why

A code review identified several edge cases where tail-queue can silently lose or corrupt messages: the broken `TEN_MINUTELY` roll pattern and non-monotonic wall-clock time can cause the sender to read and delete the file the writer is still appending to; write errors (disk full, permissions) are swallowed without informing the caller; a partial append merges two messages into one corrupted line; there is no fsync, so acknowledged messages can vanish on power loss; and failsafe/retention failure paths can drop messages or resend files forever. For a payment-grade delivery queue, "no loss" must be a guaranteed property, not a happy-path behavior.

## What Changes

- Writer appends to a dedicated active file (`<prefix>current<suffix>.open`) and rolls it via atomic rename to the time-bucket name; the sender only ever processes closed (renamed) files. This removes the "last file by name = live file" heuristic and the whole class of races around clock steps, DST fall-back, and lexicographic sort quirks.
- Fix `TEN_MINUTELY` bucket naming: compute `minute/10` explicitly instead of the invalid `yyyyMMdd-HHm` pattern (**BREAKING** for anyone relying on current TEN_MINUTELY file names).
- Time buckets computed in UTC to be immune to DST.
- Writer guarantees line framing: before appending to a file that does not end with `\n`, a `\n` is written first, so a torn previous write can never merge with the next message.
- Writer gets an explicit error contract: strict mode (default off for compat) rethrows write failures instead of only logging; optional fsync policy (`NONE`/`EVERY_MESSAGE`) via builder.
- `TailQueueSenderFailsafe`: restore the interrupt flag, and escalate (throw) if the failsafe write itself fails instead of silently dropping the message.
- Retention failure no longer causes infinite resend: a file that was sent but cannot be archived/deleted is quarantined (renamed with a `.failed` suffix) and skipped.
- Startup recovery: a stale `.open` file left by a crash is rolled into a bucket file before normal processing resumes.

## Capabilities

### New Capabilities

- `queue-writer`: durable append semantics — active-file protocol, line framing, error contract, fsync policy, bucket naming (roll cycles, UTC).
- `queue-sender`: safe consumption semantics — only closed files are read/archived, quarantine on archive failure, failsafe delivery guarantees, crash recovery.

### Modified Capabilities

<!-- none: brownfield project, no existing specs -->

## Non-goals

- Eliminating duplicate delivery (tailer + dir-sender overlap, resend after crash between send and archive). Delivery stays at-least-once; deduplication is a separate change (`reduce-duplicate-delivery`).
- Multi-process access to the same queue dir (lock file). Documented as unsupported for now.
- Automatic replay of the failsafe dead-letter directory.

## Impact

- `tail-queue-core`: `TailQueueWriterImpl`, `TailQueueDirSender`, `TailQueueFileTailer`, `TailQueueFileFilter`, `TailQueueRollCycle`, `TailQueueSenderFailsafe`, retention implementations, `TailQueueBuilder` (new options: `strictWrites`, `fsyncPolicy`).
- On-disk protocol change: new `.open` active file; existing deployments drain naturally (old bucket files are closed files and get processed as before).
- `tail-queue-test`: new tests covering each loss scenario.
- Public API: additive builder options; `TailQueueRollCycle.getDateFormatter()` replaced by a `formatBucket(Instant)` method (**BREAKING** if used externally).
