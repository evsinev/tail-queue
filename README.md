# Tail queue

Allows to write and send lines from a simple file using tail-like mechanism

## Features

* no dependencies
* small footprint
* simplest implementation which work without any side effects
* use sequential writes

## Delivery semantics

Delivery is **at-least-once**. A message can be delivered more than once - the live file is
tailed while it is being written and its content is sent again when the file is closed, and a
crash between sending a file and archiving it replays that file. **The consumer must be
idempotent** and deduplicate messages itself.

No message is lost: a message is only removed from the queue dir after it has been sent, and a
message which can neither be sent nor written to the failsafe dir keeps its file in the queue.

Only one process at a time may write to a queue dir. Multiple processes sharing a dir are not
supported and will corrupt the delivery guarantees.

## Files in a queue dir

| file                          | meaning                                                                     |
|-------------------------------|-----------------------------------------------------------------------------|
| `current.json.open`           | the active file, the writer appends to it. Only read by the sender, never deleted |
| `20260817-1000.json`          | a closed file, published by renaming the active file. Sent, then archived    |
| `20260817-1000-recovered.json`| a stale active file left by a crashed process, published on the next startup |
| `20260817-1000.json.failed`   | the file was sent but could not be archived or deleted. **Needs manual handling** |

`.open` and `.failed` files are never sent or deleted by the sender. The `.failed` case is also
reported through the metrics listener (`didSenderDirQuarantineFile`).

A message which could not be sent at all is written to the failsafe dir and published there
immediately, so a tool scanning that dir for dead letters sees every message as soon as it is
written. If publishing fails, the message stays durable in the `.open` file and is published again
on the next dead letter or at startup.

## How to add it into your app

### Maven

```xml
<repositories>
    <repository>
        <id>pne</id>
        <name>payneteasy repo</name>
        <url>https://maven.pne.io</url>
    </repository>
</repositories>
  
<dependency>
    <groupId>com.payneteasy.tail-queue</groupId>
    <artifactId>tail-queue-core</artifactId>
    <version>SEE RELEASES</version>
</dependency>
```

## Example

```java
ITailQueue queue = new TailQueueBuilder()
    .dir(new File("./queue-dir"))
    .sender(aLine -> LOG.info("Sending line {}", aLine))
    .build();

queue.startQueueSender();

ITailQueueWriter writer = queue.getWriter();

writer.writeMessage("Hello " + i);

...

queue.shutdownQueueSender();
```

## Durability options

```java
ITailQueue queue = new TailQueueBuilder()
    .dir(new File("./queue-dir"))
    .sender(aLine -> LOG.info("Sending line {}", aLine))

    // throw TailQueueWriteException instead of only logging a failed write. Default: false
    .strictWrites(true)

    // force every message to stable storage before writeMessage() returns. Default: NONE
    .fsyncPolicy(TailQueueFsyncPolicy.EVERY_MESSAGE)

    .build();
```

`fsyncPolicy(EVERY_MESSAGE)` costs an `fsync` per message and guarantees that a message survives
a power loss once `writeMessage` has returned. With `NONE` a message survives a process crash,
but may be lost if the machine loses power.

## Upgrade notes

* `TEN_MINUTELY` file names changed: the old `yyyyMMdd-HHm` pattern produced names which did not
  sort chronologically. Buckets are now `yyyyMMdd-HHmm` with the minute truncated to ten minutes.
* All bucket names are computed in **UTC** now, not in the local time zone.
* `TailQueueRollCycle.getDateFormatter()` has been removed, use `formatBucket(Instant)`.
* The writer now appends to a `.open` file and publishes it by renaming. Existing files in a queue
  dir are already closed files and are processed as before. Downgrading after a crash leaves an
  unprocessed `.open` file, which an older version will not pick up.
