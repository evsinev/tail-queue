## Why

Every message is currently delivered twice: the tailer sends the lines of the active file while it is being written, and the dir sender sends the whole file again once the writer has rolled it. At-least-once is the contract and consumers must dedup, but a steady 2x on every message is not an occasional retry — it doubles downstream load and makes a real duplicate (a retry after a crash) indistinguishable from the normal case. `fix-message-loss-edge-cases` named this as the follow-up change; this is it.

## What Changes

- A queue gets an explicit duplicate-delivery mode, selected in `TailQueueBuilder`:
  - `RESEND` (default, today's behavior): a file the tailer has read is sent again in full by the dir sender.
  - `SKIP`: a file the tailer has read to end-of-file is not sent again; the dir sender only archives it. Steady-state delivery becomes one copy per message.
- The tailer becomes stateful across sender cycles: it keeps its reader open for the lifetime of the file it is reading instead of reopening the active file from offset 0 on every cycle. This removes the re-send of already-tailed lines when the tailer leaves an active file early (a closed file appeared in the directory) or after an error, and it makes "the tailer read this file to EOF" a fact the dir sender can rely on rather than a line count both sides must agree on.
- The handoff between tailer and dir sender is in-memory: both already run in the same thread of the sender task, so no on-disk protocol changes and no offset file is introduced. A tailed file is identified after the writer's rename by its filesystem key (`BasicFileAttributes.fileKey()`, i.e. device+inode), not by its name.
- File identity is required by both modes, so queue construction fails on a filesystem that does not expose a file key instead of silently degrading — an enabled mode is never a fiction, and the tailer's progress is never quietly lost. **BREAKING** for a deployment whose queue directory is on such a filesystem (in practice Windows, and some network or FUSE mounts), which must stay on the previous version until the fallback described in design.md is implemented.
- New metric for a file that was skipped because the tailer had already delivered it, so the effect of the mode is observable in production.
- Duplicates that a process restart causes stay: a partially tailed active file is recovered and re-delivered in full. Delivery remains at-least-once in both modes.

## Capabilities

### New Capabilities

<!-- none -->

### Modified Capabilities

- `queue-sender`: adds the duplicate-delivery mode contract — what each mode guarantees, the requirement that a tailed file is identified across the writer's rename, the tailer's cross-cycle reader state, fail-fast when the mode cannot be honored, and the unchanged at-least-once guarantee across restarts.

## Impact

- `tail-queue-core`:
  - new public enum `TailQueueDuplicatePolicy` (`RESEND`, `SKIP`) and `TailQueueBuilder.duplicatePolicy(...)`; additive, default preserves current behavior.
  - `TailQueueFileTailer`: reader held across `tailOneFile()` calls, records the file key of a file it read to EOF.
  - `TailQueueDirSender`: consults that record and, in `SKIP`, archives without sending.
  - `ITailQueueMetricsListener`: new `default` method for the skipped-file event (no break for existing implementations).
  - `TailQueueBuilder.build()`: probes the queue directory for file-key support in both modes and throws when it is absent (**BREAKING** on such filesystems).
  - no new dependencies; `java.nio.file.attribute.BasicFileAttributes` is JDK.
- `tail-queue-prometheus-simpleclient`: gauge/counter for the new event.
- `tail-queue-test`: tests asserting exactly-one delivery in `SKIP` and two deliveries in `RESEND` for the same scenario.
- `README.md`: document the two modes and that at-least-once still holds.
- Public API and on-disk layout stay backward compatible; delivery behavior does not change for an existing deployment that does not set the new option, as long as its queue directory is on a filesystem exposing file identity.
