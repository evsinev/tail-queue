## 1. Public API and configuration

- [x] 1.1 Add public enum `TailQueueDuplicatePolicy` with `RESEND` and `SKIP` and javadoc stating what each mode guarantees and that both stay at-least-once
- [x] 1.2 Add `TailQueueBuilder.duplicatePolicy(TailQueueDuplicatePolicy)` defaulting to `RESEND`, with javadoc pointing at the metric that shows the mode is in effect
- [x] 1.3 Add the delivered-file record as an internal collaborator (file keys only, entry removed when consumed, bounded with a WARN when the bound is hit) and wire the same instance into the tailer and the dir sender in `TailQueueBuilder.createSenderTask()`

## 2. File identity

- [x] 2.1 Add an internal helper that reads a file's key via `Files.readAttributes(path, BasicFileAttributes.class).fileKey()` and returns `null` when the attribute or the file is absent, without throwing
- [x] 2.2 In `TailQueueBuilder.build()`, probe the queue directory (after `mkDirs`) in both modes and throw an `IllegalStateException` naming the directory and the mode if no file key is available, before any sender thread can start

## 3. Tailer keeps its position

- [x] 3.1 Hold the reader and the file key of the file being tailed as `TailQueueFileTailer` state across `tailOneFile()` calls; open a reader only when none is held and the active file exists
- [x] 3.2 Detect that the held file was rolled (its key no longer matches the key of the active file path, or the active file is gone), drain it to end of file, and close the reader — before returning from `tailOneFile()`
- [x] 3.3 Record the drained file's key as delivered only when every line was accepted; on any exception close the reader, discard the record for that file, and keep the existing error metric
- [x] 3.4 Keep the yield conditions as they are (closed file present, no new lines) and confirm a yield no longer discards the reader
- [x] 3.5 Close the held reader in the sender task's shutdown path so no descriptor outlives the queue

## 4. Dir sender honors the record

- [x] 4.1 In `SKIP`, resolve the key of each closed file before sending and, when it is in the delivered record, skip `fileSender.sendFile` and go straight to `archiveFile`, removing the entry from the record
- [x] 4.2 Keep retention, quarantine, and `sentFiles` behavior identical for a skipped file — only the send is skipped
- [x] 4.3 In `RESEND`, ignore the record entirely so the path stays byte-for-byte today's behavior

## 5. Observability

- [x] 5.1 Add a `default` method to `ITailQueueMetricsListener` for a file that was archived without being sent because the tailer had delivered it
- [x] 5.2 Implement it in `TailQueueMetricsListenerPrometheusSimpleClient` / `TailQueuePrometheusSimpleClientFactory` as a counter, following the naming of the existing `sender_dir_*` metrics
- [x] 5.3 Log at DEBUG when a file is skipped and at WARN when `SKIP` is on but a closed file's key cannot be resolved

## 6. Tests

- [x] 6.1 `RESEND`: write, tail, roll, process — assert the message is received twice (locks in today's behavior as the default)
- [x] 6.2 `SKIP`: same scenario — assert exactly one delivery, and assert the file was archived
- [x] 6.3 `SKIP`: a closed file that was never tailed (dropped into the directory, and a `-recovered` file) is sent in full
- [x] 6.4 `SKIP`: a sender that throws on one line — assert the whole file is sent by the dir sender afterwards and the failed message is delivered
- [x] 6.5 Intra-file progress: an unrelated closed file appears mid-tail — assert no line is delivered twice from the active file, in both modes
- [x] 6.6 `SKIP` on a restart: a partially tailed active file is recovered and every message is delivered at least once
- [x] 6.7 Fail-fast: `build()` throws when the file key is unavailable, in both modes (simulate via the helper's seam, without requiring an exotic filesystem)
- [x] 6.8 Run `mvn verify -Pintegration-test` and confirm the existing loss tests still pass in both modes

## 7. Documentation

- [x] 7.1 Document both modes in `README.md`: the default, how to enable `SKIP`, that at-least-once and consumer idempotency still apply, and which metric proves the mode is working
- [x] 7.2 Note in `README.md` that the sender needs a filesystem exposing a stable file key in both modes, that queue construction fails otherwise, and that this is breaking for such deployments
