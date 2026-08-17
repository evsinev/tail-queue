# Tasks: fix-message-loss-edge-cases

## 1. Roll cycle and bucket naming

- [x] 1.1 Replace `getDateFormatter()` with `formatBucket(Instant)` in `TailQueueRollCycle`; UTC; TEN_MINUTELY via `minute/10*10` truncation
- [x] 1.2 Property/unit test: bucket names sort chronologically for all cycles across hour/day boundaries

## 2. Writer: active file protocol

- [x] 2.1 Rework `TailQueueWriterImpl`: persistent `FileChannel` on `<prefix>current<suffix>.open`; roll = force (if fsync) + atomic rename to bucket name + reopen
- [x] 2.2 Framing repair: on opening a non-empty active file, append `\n` if last byte is not `\n`
- [x] 2.3 Startup recovery: roll a stale `.open` file before the sender thread starts (`TailQueueBuilder.build()` / `TailQueueImpl.startQueueSender()`)
- [x] 2.4 Builder options `strictWrites` and `fsyncPolicy`; `TailQueueWriteException`
- [x] 2.5 Tests: torn-write merge scenario, roll-on-bucket-change, clock-backwards (injectable clock), strict-mode throw, fsync smoke

## 3. Sender: consume only closed files

- [x] 3.1 `TailQueueFileFilter`: exclude `.open` and `.failed`; drop the "skip last file" logic in `TailQueueDirSender.createFileListForDirProcess()`
- [x] 3.2 `TailQueueFileTailer`: tail the `.open` file (exact name) instead of "the only matching file"
- [x] 3.3 Quarantine: on retention failure rename to `<name>.failed`, in-memory sent-set fallback, new metrics callback
- [x] 3.4 Tests: live file never deleted under concurrent writes, quarantine on read-only dir, restart recovery delivers stale `.open` content

## 4. Failsafe hardening

- [x] 4.1 Restore interrupt flag in `TailQueueSenderFailsafe`
- [x] 4.2 Fail-writer in strict mode; propagate failsafe write failure
- [x] 4.3 Tests: failsafe dir unwritable → source file not archived; interrupt during retries keeps flag set

## 5. Docs

- [x] 5.1 README: delivery semantics (at-least-once, consumer must dedup), `.open`/`.failed` file meanings, single-process constraint, new builder options, TEN_MINUTELY name change release note
