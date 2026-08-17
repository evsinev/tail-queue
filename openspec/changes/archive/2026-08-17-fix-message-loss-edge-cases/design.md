# Design: fix-message-loss-edge-cases

## Context

Today the writer names files by `LocalDateTime.now()` (local zone) and the sender assumes "last file by name = live file". Three independent mechanisms break that assumption: the invalid `yyyyMMdd-HHm` TEN_MINUTELY pattern (non-padded minute breaks lexicographic order), DST fall-back / NTP steps (writer re-opens old names), and a GC pause between name computation and append. Each leads to the sender deleting a file that still receives appends.

## Decision 1: Writer-owned active file with atomic rename (chosen)

The writer appends to `<prefix>current<suffix>.open`. On the first write of a new bucket, it renames the file to `<prefix><bucket><suffix>` (`Files.move`, `ATOMIC_MOVE`, fallback to plain move) and opens a fresh `.open`. The sender's file filter matches only bucket-named files; the tailer tails the `.open` file.

Consequences:

- "Do not send last file" heuristic is deleted entirely — the sender processes *all* matching files, which also fixes the "idle writer leaves last bucket file stuck" latency case.
- Clock direction becomes irrelevant for safety: bucket names only affect grouping, never ownership.
- Startup: if `.open` exists, roll it immediately (name = its first-line bucket if parseable, else current bucket) before starting the sender thread.

### Alternative considered: UTC + writer publishes protected filename

Keep per-bucket append files; writer exposes the current file name via shared state and the sender skips it. Rejected: TOCTOU window remains (sender lists and checks before the writer moves backwards after a clock step), and the migration from local-time names to UTC names breaks the "last by name" heuristic during the transition anyway.

## Decision 2: Bucket computation

`TailQueueRollCycle` gains `String formatBucket(Instant now)`; `getDateFormatter()` is removed (internal use only today). TEN_MINUTELY = `yyyyMMdd-HHmm` with minute truncated to `minute/10*10`. All buckets use `ZoneOffset.UTC`.

## Decision 3: Framing repair on append

Writer keeps the `FileOutputStream`/`FileChannel` open per active file (also removes the open-per-message overhead). On opening an existing non-empty `.open` file, it checks the last byte and writes `\n` if missing. Message encoding (strip CR/LF, append `\n`) stays as is.

## Decision 4: Error contract and fsync

Builder options:

- `strictWrites(boolean)` — default `false` (compat). Strict: rethrow as `TailQueueWriteException` (unchecked).
- `fsyncPolicy(NONE | EVERY_MESSAGE)` — default `NONE`. `EVERY_MESSAGE` calls `FileChannel.force(false)` after the write, and forces before the rename on roll.

## Decision 5: Quarantine on archive failure

`TailQueueDirSender` on retention failure: rename file to `<name>.failed`; if the rename also fails, remember the name in an in-memory "sent" set for the process lifetime to prevent resend loops. Quarantined files are excluded by the file filter and require manual/ops handling (documented).

## Decision 6: Failsafe hardening

- `Thread.currentThread().interrupt()` in the `InterruptedException` branch.
- The internal fail-writer runs in strict mode; its exception propagates to the sender task, which leaves the source file unarchived (natural retry).

## Risks / migration

- On-disk protocol adds `.open` files; old deployments upgrade cleanly (existing bucket files are just closed files). Downgrade after a crash could leave an unprocessed `.open` file — release note.
- `formatBucket` change is breaking only for external users of `getDateFormatter()` (unlikely; keep a deprecated delegate for one release if desired).
- Duplicates from tailer + dir-sender overlap remain by design (at-least-once); explicitly out of scope.

## Test strategy

JUnit tests per scenario in the spec deltas: torn-write merge repro (write partial bytes, restart writer), TEN_MINUTELY ordering property test, roll-rename atomicity under concurrent tailing, archive-failure quarantine (read-only dir), failsafe failure propagation, interrupt-flag preservation, fsync smoke test (behavioral only).
