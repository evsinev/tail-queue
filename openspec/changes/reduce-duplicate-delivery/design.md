## Context

See proposal.md — Why. The mechanics that shape this design, as the code stands today:

- `TailQueueSenderTask.run()` calls `dirSender.processDir()` and then `fileTailer.tailOneFile()` in **one thread**, in that order, once per cycle. The tailer and the dir sender never run concurrently, so anything they need to agree on can live in memory.
- `TailQueueFileTailer.tailOneFile()` opens `<prefix>current<suffix>.open` **from offset 0 on every call** and keeps its reader only for the duration of that call. It loops on `readLine()`, sleeping `liveWaitDuration` at end of file, and returns as soon as any closed file exists in the directory.
- `TailQueueWriterImpl.roll()` publishes the active file by `rename`, so the inode the tailer is reading survives the roll under a new name; the reader keeps reading it to its true end of file, and the writer creates a fresh active file for new messages.
- `TailQueueDirSender.processDir()` then sends every closed file in full via `ITailQueueFileSender` and hands it to retention.

That is the exact 2x. Two smaller sources of extra copies come from the same "reopen from 0" property: the tailer yields on *any* closed file, including one unrelated to the file it is tailing, and it also returns on an error mid-file — in both cases the next cycle re-reads the active file from its first line.

Constraints: Java 8, no new dependencies in core, at-least-once is the delivery contract, and the on-disk protocol was just stabilized by `fix-message-loss-edge-cases` — this change should not touch it.

## Goals / Non-Goals

**Goals:**

- One delivery per message in the normal case, in `SKIP` mode, without changing the on-disk protocol.
- Remove the intra-file re-sends (early yield, error mid-file) in both modes, since they are a side effect of the same design flaw.
- Keep the sender's destructive-operation ownership where it is: retention, quarantine, and metrics stay in the dir sender.

**Non-Goals:**

- Persisting tailer progress across a restart (no offset file, no sidecar, no fsync on the read path). See specs — "A restart may duplicate but must not lose".
- Deduplicating on the consumer side or adding message ids.
- Changing when the tailer yields, i.e. the latency behavior of the sender cycle.
- Supporting several processes tailing one directory, which is already unsupported.

## Decisions

### D1: Hand off in memory, not through a file

The tailer and the dir sender are the same thread of the same object graph, so the handoff is a shared in-memory record owned by the sender task and injected into both. A sidecar offset file would add a write (and, to be worth anything, an fsync) per message on the read path, and would need its own crash-recovery rules — all to remove duplicates that only occur on restart.

Alternative considered: the two-phase roll handshake (writer renames to `<bucket>.open`, tailer publishes `<bucket>` after draining). It needs no file identity trick and works on any filesystem, but it changes the on-disk protocol a second release in a row, moves publication out of the writer, and makes an external tool watching the directory wait for the sender. Rejected for now; it stays the fallback if file identity turns out to be unreliable in the field.

### D2: Keep the reader open across cycles instead of counting lines

The tailer holds its `TailQueueStrictLineReader` (and the file key it belongs to) as state between `tailOneFile()` calls, and closes it only when the file it reads has been rolled and drained. The unit of progress is then "the reader position", which needs no agreement between components.

Alternative considered — the line count the issue suggests (tailer records N lines sent, dir sender skips N lines): it requires `TailQueueStrictLineReader` and `TailQueueFileSenderImpl`'s `LineNumberReader` to count lines identically forever (they do not agree on empty lines today: the strict reader returns `null` for an empty line and the `LineNumberReader` returns `""`), and it makes every future change to either reader a potential silent off-by-one that skips a payment message. A byte offset is worse: the strict reader decodes UTF-8 chars and buffers a partial line, so its byte position is not observable.

Consequence worth naming: with the reader held open, the fix for the intra-file re-sends is free and applies to `RESEND` too — the tailer simply never re-reads a line it already read.

### D3: Identify a file by its filesystem key, not by its name

`Files.readAttributes(path, BasicFileAttributes.class).fileKey()` (device+inode on POSIX, file id on Windows) is the identity of the file across the writer's rename. The dir sender resolves the key of each closed file it is about to send and skips sending when that key is in the delivered record.

Names cannot be used: the file the tailer reads is `current.json.open` while it reads it and `20260817-1230.json` afterwards, and bucket names are reused across runs (`freeBucketFile` even appends `-1`, `-2` variants), so a name match could skip a *different* file's content — a silent message loss. A key match cannot: an inode is not reused while the file exists.

Reading a key can fail for two very different reasons, and they must not be confused. "There is no key because the active file is gone" means the writer rolled it; "there is no key because the attributes could not be read this time" means nothing at all. Treating the second as a roll would make the tailer stop at the current end of a file the writer is still appending to and record it as delivered, losing everything appended afterwards — so the tailer only concludes "rolled" when the active file is really absent, and otherwise keeps reading and retries on the next cycle.

### D4: Finalize the tailed file before yielding, not on the next cycle

Because `processDir()` runs *before* `tailOneFile()` in a cycle, a record written "next time the tailer runs" would arrive after the dir sender had already sent the file. So when the tailer is about to yield, it checks whether the file it holds is still the active one; if it is not, the file was rolled, and the tailer drains it to end of file, records its key as delivered, and closes the reader — all before returning. The sender task's cycle order stays as it is.

### D5: A file is recorded as delivered only on a clean drain

The record is written only when the tailer reached end of file with every line accepted by `ITailQueueSender.sendMessage` (which, in a normal setup, is `TailQueueSenderFailsafe` and therefore only throws when the dead-letter write itself failed). Any exception on the way discards the record for that file and closes the reader, so the file falls back to a full send by the dir sender: duplicates for the lines already delivered, loss for none. This is what "Deduplication never trades away durability" in the specs requires.

Two more ways the drain can end short of that, both of which must leave the file unrecorded:

- **The file does not end with a new line.** `TailQueueStrictLineReader` keeps an unterminated line in its buffer and never returns it, while the `LineNumberReader` of the dir sender hands it out as a line. So a rolled file whose last bytes are unterminated has *not* been fully delivered by the tailer, even though its drain reached end of file. The writer keeps that from happening today — it only rolls a file it has just written a complete line to, and repairs the framing of a torn write when it reopens the file — but that invariant lives in another class and an external tool appending to the `.open` file does not know about it. The tailer therefore asks the reader whether a partial line is left and, if so, hands the file to the dir sender instead of recording it. One check buys immunity from a silent loss; the cost when it fires is one duplicated file.
- **The sender was interrupted mid-drain.** The drain is the one place where the tailer sends lines without checking the interrupt flag, and with a failing delegate every one of them would run through the failsafe path into a dead letter during shutdown. Checking the flag per line and dropping the record means those messages stay in the file and are delivered normally by the next run.

### D6: `RESEND` stays the default and is a mode, not a switch to keep dead code alive

`RESEND` is the behavior of every deployment today, so it stays the default; a library upgrade must not silently halve the number of deliveries a downstream consumer sees. Mechanically, both modes share the same tailer (with D2 and D4 always active); the mode only decides whether the dir sender consults the delivered record. That keeps a single code path for the risky part.

### D7: Probe file-key support once, at construction, for both modes

`build()` reads the file key of the queue directory (creating it first, as it already does) and throws if it is `null`, naming the directory and the selected mode. Probing at construction rather than per file means a misconfigured deployment fails at startup instead of degrading hours later under a log line nobody reads.

The probe applies to `RESEND` as well, not only to `SKIP`, because D2 and D4 are always active: the tailer will not open a file whose identity it cannot record, since it could otherwise end up holding one file while its key names another. On a filesystem without keys the tailer therefore delivers nothing live and every message waits for its file to be rolled — a silent latency regression, not a loss. Requiring identity unconditionally keeps one code path instead of two and makes that regression impossible to ship unnoticed.

This is breaking on a filesystem whose `fileKey()` is `null`, in practice Windows and some network or FUSE mounts. Alternatives rejected: a heuristic roll detection from size, `lastModifiedTime` or `creationTime` reintroduces exactly the kind of guessing that `fix-message-loss-edge-cases` removed, and a keyless fallback that closes the reader on yield leaves a documented requirement unmet on that platform while doubling the tailer's state machine. If such a deployment appears, the two-phase roll handshake from D1 is the fallback to implement, since it needs no file identity at all.

### D8: A record never outlives the file it names

A file key is the identity of an *existing* file: once the file is unlinked, the filesystem may hand the same key to a new one. So an entry which outlives its file is not merely useless, it is dangerous — a later closed file which the tailer never read could match it and be archived without being sent. Two rules keep that from happening:

- The tailer finalizes a rolled file at the **start** of the sender cycle, before the dir sender lists the directory (`finishPendingRoll`). Otherwise the sequence "tailer yields on an unrelated closed file → writer rolls → dir sender sends and deletes the file → tailer finally drains it" would both duplicate that file and leave behind a record for a deleted inode.
- Before recording, the tailer checks that a closed file with that key is still in the queue dir. This covers whatever the cycle order cannot: a file removed by an operator, an archiver moving it away, a retention which deleted it out of band.

Rejected alternative: recording the key regardless and treating a leftover entry as harmless. It is only harmless until an inode number is reused, which ext4 and xfs do readily, and the failure mode then is a silently archived file — the exact thing this library must never do.

### D9: The record is small, and entries are evicted when used

The delivered record holds file keys only, and the dir sender removes an entry when it consumes it (skips-and-archives that file). Since both run in one thread on every cycle, at most a handful of entries exist at a time. A defensive upper bound with a warning keeps a pathological case (retention permanently failing and quarantining files) from growing it without limit.

## Risks / Trade-offs

- **`fileKey()` returns `null` on some filesystem (Windows, network mount, exotic FUSE)** → the queue refuses to start in either mode (D7), which is a breaking change for such a deployment. If the value were unstable rather than absent, a mismatch degrades to sending the file again — a duplicate, never a loss.
- **A held-open reader keeps a descriptor on a rolled or deleted file** → one descriptor per queue directory, released as soon as the file is drained and recorded; on POSIX a rolled file is only unlinked by retention after the dir sender has processed it.
- **A file that is never rolled (writer idle) keeps the reader open indefinitely** → this is the intended behavior; the file is still being appended to, and the descriptor is the same one the current code opens every cycle anyway.
- **`SKIP` makes a real duplicate rare, so a consumer whose idempotency is broken stops being covered by the constant 2x** → duplicates still occur (restart, send error, backlog re-send), the contract stays at-least-once, and `RESEND` remains one builder call away. Documented in the README as part of this change.
- **Two writers/senders on the same directory** → unchanged and still unsupported; with `SKIP` a second sender would simply send what the first one already delivered (duplicate, not loss).

## Migration Plan

1. Ship with the default `RESEND`: no delivery-behavior change for an existing deployment, so the release is a drop-in — except on a filesystem without file identity, where the queue now refuses to start (see D7). Verify at rollout that the queue directories are on a local POSIX filesystem.
2. Enable `SKIP` per queue where the consumer's idempotency has been verified; watch the skipped-file metric to confirm the mode is actually taking effect (a flat zero with a non-empty queue means files are not being matched).
3. Rollback is a builder change back to `RESEND` and a restart; no on-disk state to undo, since none is written.
